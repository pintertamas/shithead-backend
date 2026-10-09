package store

import (
	"context"

	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// DynamoDB is the subset of the DynamoDB client used by the store. The real
// *dynamodb.Client satisfies it; tests can supply a fake.
type DynamoDB interface {
	GetItem(ctx context.Context, params *dynamodb.GetItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error)
	PutItem(ctx context.Context, params *dynamodb.PutItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.PutItemOutput, error)
	UpdateItem(ctx context.Context, params *dynamodb.UpdateItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.UpdateItemOutput, error)
	DeleteItem(ctx context.Context, params *dynamodb.DeleteItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.DeleteItemOutput, error)
	Query(ctx context.Context, params *dynamodb.QueryInput, optFns ...func(*dynamodb.Options)) (*dynamodb.QueryOutput, error)
	Scan(ctx context.Context, params *dynamodb.ScanInput, optFns ...func(*dynamodb.Options)) (*dynamodb.ScanOutput, error)
	BatchGetItem(ctx context.Context, params *dynamodb.BatchGetItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.BatchGetItemOutput, error)
	TransactWriteItems(ctx context.Context, params *dynamodb.TransactWriteItemsInput, optFns ...func(*dynamodb.Options)) (*dynamodb.TransactWriteItemsOutput, error)
}

// Tables names the DynamoDB tables used by the backend.
type Tables struct {
	Games       string
	Users       string
	Connections string
}

// Store bundles the three repositories over one DynamoDB client.
type Store struct {
	Games       *Games
	Users       *Users
	Connections *Connections
}

// New creates the repositories for the given tables.
func New(db DynamoDB, tables Tables) *Store {
	return &Store{
		Games:       &Games{db: db, table: tables.Games},
		Users:       &Users{db: db, table: tables.Users},
		Connections: &Connections{db: db, table: tables.Connections},
	}
}

// decodeItem unmarshals an item, leaving fields absent from the item at the
// values set in out beforehand (used for legacy defaults).
func decodeItem(item map[string]types.AttributeValue, out any) error {
	return attributevalue.UnmarshalMap(item, out)
}

// stringAttr reads an S attribute, returning "" when it is absent.
func stringAttr(item map[string]types.AttributeValue, name string) string {
	if v, ok := item[name].(*types.AttributeValueMemberS); ok {
		return v.Value
	}
	return ""
}

func strKey(name, value string) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{name: &types.AttributeValueMemberS{Value: value}}
}
