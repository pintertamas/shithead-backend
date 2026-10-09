package main

import (
	"context"
	"crypto/rand"
	"fmt"
	"math/big"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// Dynamo is the subset of the DynamoDB client the glue functions use.
// *dynamodb.Client satisfies it; tests use an in-memory fake.
type Dynamo interface {
	GetItem(ctx context.Context, params *dynamodb.GetItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.GetItemOutput, error)
	PutItem(ctx context.Context, params *dynamodb.PutItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.PutItemOutput, error)
	UpdateItem(ctx context.Context, params *dynamodb.UpdateItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.UpdateItemOutput, error)
	DeleteItem(ctx context.Context, params *dynamodb.DeleteItemInput, optFns ...func(*dynamodb.Options)) (*dynamodb.DeleteItemOutput, error)
	Query(ctx context.Context, params *dynamodb.QueryInput, optFns ...func(*dynamodb.Options)) (*dynamodb.QueryOutput, error)
	Scan(ctx context.Context, params *dynamodb.ScanInput, optFns ...func(*dynamodb.Options)) (*dynamodb.ScanOutput, error)
}

const sessionCodeAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

const sessionCodeLength = 6

// randomSessionCode returns a random 6-character code, as the Python create_game did.
func randomSessionCode() (string, error) {
	code := make([]byte, sessionCodeLength)
	base := big.NewInt(int64(len(sessionCodeAlphabet)))
	for i := range code {
		index, err := rand.Int(rand.Reader, base)
		if err != nil {
			return "", fmt.Errorf("random session code: %w", err)
		}
		code[i] = sessionCodeAlphabet[index.Int64()]
	}
	return string(code), nil
}

// getItem returns the item with the given single-attribute key, or nil when absent.
func (a *App) getItem(ctx context.Context, table, keyAttr, keyValue string) (map[string]types.AttributeValue, error) {
	out, err := a.db.GetItem(ctx, &dynamodb.GetItemInput{
		TableName: &table,
		Key:       key(keyAttr, keyValue),
	})
	if err != nil {
		return nil, fmt.Errorf("get %s item: %w", table, err)
	}
	return out.Item, nil
}

// putItem writes an item built from plain Go values using the DynamoDB attribute tags.
func (a *App) putItem(ctx context.Context, table string, item map[string]any) error {
	av, err := marshalItem(item)
	if err != nil {
		return err
	}
	if _, err := a.db.PutItem(ctx, &dynamodb.PutItemInput{TableName: &table, Item: av}); err != nil {
		return fmt.Errorf("put %s item: %w", table, err)
	}
	return nil
}
