package main

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// voiceFakeDynamo adds to the shared fakeDynamo what the voice counter needs: an
// UpdateItem with a numeric ADD clause, and ReturnValues on UpdateItem and DeleteItem.
// Everything else goes to the shared fake.
type voiceFakeDynamo struct {
	*fakeDynamo
}

// newVoiceTestApp builds an App over voiceFakeDynamo with the fixed test clock.
func newVoiceTestApp(db *fakeDynamo) *App {
	return newTestApp(voiceFakeDynamo{fakeDynamo: db})
}

// UpdateItem applies "ADD #name :value" to a number attribute and returns the item.
// Other update expressions are delegated to the shared fake.
func (f voiceFakeDynamo) UpdateItem(ctx context.Context, in *dynamodb.UpdateItemInput, opts ...func(*dynamodb.Options)) (*dynamodb.UpdateItemOutput, error) {
	expr := ""
	if in.UpdateExpression != nil {
		expr = *in.UpdateExpression
	}
	if !strings.HasPrefix(expr, "ADD ") {
		return f.fakeDynamo.UpdateItem(ctx, in, opts...)
	}
	fields := strings.Fields(strings.TrimPrefix(expr, "ADD "))
	if len(fields) != 2 {
		panic("voice fake: unsupported ADD expression " + expr)
	}
	attr, ok := in.ExpressionAttributeNames[fields[0]]
	if !ok {
		panic("voice fake: unknown name " + fields[0])
	}
	delta, err := numberOperand(in.ExpressionAttributeValues[fields[1]])
	if err != nil {
		panic("voice fake: " + err.Error())
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	f.record("UpdateItem:" + *in.TableName)
	table := f.table(*in.TableName)
	id := f.keyOf(*in.TableName, in.Key)
	item := table[id]
	if item == nil {
		item = copyItem(in.Key)
	}
	current, _ := numberAttr(item, attr)
	item[attr] = &types.AttributeValueMemberN{Value: strconv.FormatInt(current+delta, 10)}
	table[id] = item
	return &dynamodb.UpdateItemOutput{Attributes: copyItem(item)}, nil
}

// DeleteItem with ReturnValues ALL_OLD returns the removed item, or no attributes when
// there was none. Other deletes are delegated to the shared fake.
func (f voiceFakeDynamo) DeleteItem(ctx context.Context, in *dynamodb.DeleteItemInput, opts ...func(*dynamodb.Options)) (*dynamodb.DeleteItemOutput, error) {
	if in.ReturnValues != types.ReturnValueAllOld {
		return f.fakeDynamo.DeleteItem(ctx, in, opts...)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.record("DeleteItem:" + *in.TableName)
	table := f.table(*in.TableName)
	id := f.keyOf(*in.TableName, in.Key)
	old := table[id]
	delete(table, id)
	return &dynamodb.DeleteItemOutput{Attributes: copyItem(old)}, nil
}

// numberOperand reads an N value used as an update operand.
func numberOperand(value types.AttributeValue) (int64, error) {
	n, ok := value.(*types.AttributeValueMemberN)
	if !ok {
		return 0, fmt.Errorf("operand is not a number: %T", value)
	}
	return strconv.ParseInt(n.Value, 10, 64)
}
