package main

import (
	"context"
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
	delete(f.table(*in.TableName), f.keyOf(*in.TableName, in.Key))
	return &dynamodb.DeleteItemOutput{}, nil
}

func (f *fakeDynamo) Query(_ context.Context, in *dynamodb.QueryInput, _ ...func(*dynamodb.Options)) (*dynamodb.QueryOutput, error) {
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
