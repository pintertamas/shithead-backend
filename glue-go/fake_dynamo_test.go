package main

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"sync"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

const (
	testGames       = "games"
	testUsers       = "users"
	testConnections = "connections"
)

// fakeDynamo is an in-memory Dynamo. It understands only what the glue uses:
// single-attribute keys, the user_id and game_session_id indexes, and SET
// clauses of the form "a = :v" and "a = if_not_exists(a, :v)".
type fakeDynamo struct {
	mu         sync.Mutex
	keyAttrs   map[string]string
	indexAttrs map[string]string
	tables     map[string]map[string]map[string]types.AttributeValue
	ops        []string
	// queryErr, when set, is returned by every Query.
	queryErr error
	// onQuery runs before a Query is answered (outside the lock), to simulate a concurrent save.
	onQuery func()
}

func newFakeDynamo() *fakeDynamo {
	return &fakeDynamo{
		keyAttrs: map[string]string{
			testGames:       "game_id",
			testUsers:       "user_id",
			testConnections: "connection_id",
		},
		indexAttrs: map[string]string{
			"user_id-index":         "user_id",
			"game_session_id-index": "game_session_id",
		},
		tables: map[string]map[string]map[string]types.AttributeValue{},
	}
}

// seed stores an item directly, bypassing the glue.
func (f *fakeDynamo) seed(table string, item map[string]types.AttributeValue) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.table(table)[itemKey(item[f.keyAttrs[table]])] = item
}

// item returns a copy of a stored item, or nil.
func (f *fakeDynamo) item(table, keyValue string) map[string]types.AttributeValue {
	f.mu.Lock()
	defer f.mu.Unlock()
	return copyItem(f.table(table)[keyValue])
}

// writes returns how many mutating calls were made, optionally filtered by operation.
func (f *fakeDynamo) writes(op string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	count := 0
	for _, recorded := range f.ops {
		if strings.HasPrefix(recorded, op) {
			count++
		}
	}
	return count
}

func (f *fakeDynamo) record(op string) {
	f.ops = append(f.ops, op)
}

func (f *fakeDynamo) table(name string) map[string]map[string]types.AttributeValue {
	if f.tables[name] == nil {
		f.tables[name] = map[string]map[string]types.AttributeValue{}
	}
	return f.tables[name]
}

func (f *fakeDynamo) keyOf(table string, key map[string]types.AttributeValue) string {
	return itemKey(key[f.keyAttrs[table]])
}

func (f *fakeDynamo) GetItem(_ context.Context, in *dynamodb.GetItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return &dynamodb.GetItemOutput{Item: copyItem(f.table(*in.TableName)[f.keyOf(*in.TableName, in.Key)])}, nil
}

func (f *fakeDynamo) PutItem(_ context.Context, in *dynamodb.PutItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.PutItemOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.record("PutItem:" + *in.TableName)
	f.table(*in.TableName)[itemKey(in.Item[f.keyAttrs[*in.TableName]])] = copyItem(in.Item)
	return &dynamodb.PutItemOutput{}, nil
}

func (f *fakeDynamo) UpdateItem(_ context.Context, in *dynamodb.UpdateItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.UpdateItemOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.record("UpdateItem:" + *in.TableName)
	table := f.table(*in.TableName)
	id := f.keyOf(*in.TableName, in.Key)
	item := table[id]
	if item == nil {
		item = copyItem(in.Key)
	}
	applyUpdate(item, *in.UpdateExpression, in.ExpressionAttributeValues)
	table[id] = item
	return &dynamodb.UpdateItemOutput{}, nil
}

func (f *fakeDynamo) DeleteItem(_ context.Context, in *dynamodb.DeleteItemInput, _ ...func(*dynamodb.Options)) (*dynamodb.DeleteItemOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.record("DeleteItem:" + *in.TableName)
	table := f.table(*in.TableName)
	id := f.keyOf(*in.TableName, in.Key)
	if in.ConditionExpression != nil {
		ok := evalCondition(*in.ConditionExpression, table[id], in.ExpressionAttributeNames, in.ExpressionAttributeValues)
		if !ok {
			return nil, &types.ConditionalCheckFailedException{Message: strPtr("The conditional request failed")}
		}
	}
	delete(table, id)
	return &dynamodb.DeleteItemOutput{}, nil
}

func (f *fakeDynamo) Scan(_ context.Context, in *dynamodb.ScanInput, _ ...func(*dynamodb.Options)) (*dynamodb.ScanOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var items []map[string]types.AttributeValue
	for _, item := range f.table(*in.TableName) {
		items = append(items, copyItem(item))
	}
	return &dynamodb.ScanOutput{Items: items}, nil
}

func (f *fakeDynamo) Query(_ context.Context, in *dynamodb.QueryInput, _ ...func(*dynamodb.Options)) (*dynamodb.QueryOutput, error) {
	if f.onQuery != nil {
		f.onQuery()
	}
	if f.queryErr != nil {
		return nil, f.queryErr
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	attr := f.indexAttrs[*in.IndexName]
	var want string
	for _, value := range in.ExpressionAttributeValues {
		want = itemKey(value)
	}
	var matches []map[string]types.AttributeValue
	for _, item := range f.table(*in.TableName) {
		if itemKey(item[attr]) == want {
			matches = append(matches, copyItem(item))
		}
	}
	return &dynamodb.QueryOutput{Items: matches}, nil
}

// applyUpdate applies the SET clauses the glue sends.
func applyUpdate(item map[string]types.AttributeValue, expr string, values map[string]types.AttributeValue) {
	for _, clause := range splitTopLevel(strings.TrimPrefix(expr, "SET ")) {
		name, rhs, _ := strings.Cut(clause, " = ")
		name, rhs = strings.TrimSpace(name), strings.TrimSpace(rhs)
		if inner, ok := strings.CutPrefix(rhs, "if_not_exists("); ok {
			_, valueName, _ := strings.Cut(strings.TrimSuffix(inner, ")"), ", ")
			if _, exists := item[name]; !exists {
				item[name] = values[strings.TrimSpace(valueName)]
			}
			continue
		}
		item[name] = values[rhs]
	}
}

// splitTopLevel splits on commas that are not inside parentheses.
func splitTopLevel(expr string) []string {
	var parts []string
	depth, start := 0, 0
	for i, r := range expr {
		switch r {
		case '(':
			depth++
		case ')':
			depth--
		case ',':
			if depth == 0 {
				parts = append(parts, strings.TrimSpace(expr[start:i]))
				start = i + 1
			}
		}
	}
	return append(parts, strings.TrimSpace(expr[start:]))
}

func itemKey(value types.AttributeValue) string {
	if v, ok := value.(*types.AttributeValueMemberS); ok {
		return v.Value
	}
	return ""
}

func copyItem(item map[string]types.AttributeValue) map[string]types.AttributeValue {
	if item == nil {
		return nil
	}
	out := make(map[string]types.AttributeValue, len(item))
	for k, v := range item {
		out[k] = v
	}
	return out
}

// evalCondition judges a ConditionExpression against an item (nil when absent).
// It understands AND, OR, parentheses, attribute_not_exists(a) and the comparisons
// = < <= > >= between an attribute (#name) and a value (:value). Anything else
// panics, so a test never passes on a condition the fake cannot judge.
func evalCondition(expr string, item map[string]types.AttributeValue, names map[string]string, values map[string]types.AttributeValue) bool {
	p := &condParser{tokens: tokenizeCondition(expr), item: item, names: names, values: values}
	result := p.or()
	if p.pos != len(p.tokens) {
		panic("unexpected token in condition: " + expr)
	}
	return result
}

type condParser struct {
	tokens []string
	pos    int
	item   map[string]types.AttributeValue
	names  map[string]string
	values map[string]types.AttributeValue
}

func tokenizeCondition(expr string) []string {
	spaced := strings.NewReplacer(
		"(", " ( ", ")", " ) ", "<=", " <= ", ">=", " >= ", "=", " = ", "<", " < ", ">", " > ",
	).Replace(expr)
	return strings.Fields(spaced)
}

func (p *condParser) peek() string {
	if p.pos >= len(p.tokens) {
		return ""
	}
	return p.tokens[p.pos]
}

func (p *condParser) next() string {
	tok := p.peek()
	p.pos++
	return tok
}

func (p *condParser) or() bool {
	result := p.and()
	for p.peek() == "OR" {
		p.next()
		right := p.and()
		result = result || right
	}
	return result
}

func (p *condParser) and() bool {
	result := p.primary()
	for p.peek() == "AND" {
		p.next()
		right := p.primary()
		result = result && right
	}
	return result
}

func (p *condParser) primary() bool {
	switch p.peek() {
	case "(":
		p.next()
		result := p.or()
		p.expect(")")
		return result
	case "attribute_not_exists":
		p.next()
		p.expect("(")
		name := p.operand()
		p.expect(")")
		return name == nil
	}
	left := p.operand()
	op := p.next()
	right := p.operand()
	return compareCondition(left, op, right)
}

func (p *condParser) expect(tok string) {
	if got := p.next(); got != tok {
		panic(fmt.Sprintf("condition: expected %q, got %q", tok, got))
	}
}

// operand resolves #name to the item's attribute (nil when absent) and :value to its value.
func (p *condParser) operand() types.AttributeValue {
	tok := p.next()
	switch {
	case strings.HasPrefix(tok, "#"):
		name, ok := p.names[tok]
		if !ok {
			panic("condition: unknown name " + tok)
		}
		return p.item[name]
	case strings.HasPrefix(tok, ":"):
		value, ok := p.values[tok]
		if !ok {
			panic("condition: unknown value " + tok)
		}
		return value
	}
	panic("condition: unexpected operand " + tok)
}

func compareCondition(left types.AttributeValue, op string, right types.AttributeValue) bool {
	if left == nil || right == nil {
		return false
	}
	cmp, ok := compareAttributes(left, right)
	if !ok {
		return false
	}
	switch op {
	case "=":
		return cmp == 0
	case "<":
		return cmp < 0
	case "<=":
		return cmp <= 0
	case ">":
		return cmp > 0
	case ">=":
		return cmp >= 0
	}
	panic("condition: unsupported operator " + op)
}

// compareAttributes orders two values of the same type (N, S or BOOL equality).
func compareAttributes(a, b types.AttributeValue) (int, bool) {
	switch av := a.(type) {
	case *types.AttributeValueMemberN:
		bv, ok := b.(*types.AttributeValueMemberN)
		if !ok {
			return 0, false
		}
		x, errA := strconv.ParseFloat(av.Value, 64)
		y, errB := strconv.ParseFloat(bv.Value, 64)
		if errA != nil || errB != nil {
			return 0, false
		}
		return compareFloats(x, y), true
	case *types.AttributeValueMemberS:
		bv, ok := b.(*types.AttributeValueMemberS)
		if !ok {
			return 0, false
		}
		return strings.Compare(av.Value, bv.Value), true
	case *types.AttributeValueMemberBOOL:
		bv, ok := b.(*types.AttributeValueMemberBOOL)
		if !ok {
			return 0, false
		}
		if av.Value == bv.Value {
			return 0, true
		}
		return 1, true
	}
	return 0, false
}

func compareFloats(x, y float64) int {
	switch {
	case x < y:
		return -1
	case x > y:
		return 1
	}
	return 0
}
