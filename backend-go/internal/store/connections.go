package store

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// Connections is the repository for the WebSocket connection registry
// (key connection_id, index game_session_id-index).
type Connections struct {
	db    DynamoDB
	table string
}

// Put registers a connection.
func (c *Connections) Put(ctx context.Context, record ConnectionRecord) error {
	item, err := attributevalue.MarshalMap(record)
	if err != nil {
		return fmt.Errorf("marshal connection: %w", err)
	}
	if _, err := c.db.PutItem(ctx, &dynamodb.PutItemInput{TableName: &c.table, Item: item}); err != nil {
		return fmt.Errorf("put connection %s: %w", record.ConnectionID, err)
	}
	return nil
}

// UserID returns the user bound to a connection, or "" when unknown.
func (c *Connections) UserID(ctx context.Context, connectionID string) (string, error) {
	out, err := c.db.GetItem(ctx, &dynamodb.GetItemInput{
		TableName:      &c.table,
		Key:            strKey("connection_id", connectionID),
		ConsistentRead: boolPtr(true),
	})
	if err != nil {
		return "", fmt.Errorf("get connection %s: %w", connectionID, err)
	}
	return stringAttr(out.Item, "user_id"), nil
}

// Delete removes a connection record.
func (c *Connections) Delete(ctx context.Context, connectionID string) error {
	_, err := c.db.DeleteItem(ctx, &dynamodb.DeleteItemInput{TableName: &c.table, Key: strKey("connection_id", connectionID)})
	if err != nil {
		return fmt.Errorf("delete connection %s: %w", connectionID, err)
	}
	return nil
}

// ForSession returns every connection subscribed to a game session.
func (c *Connections) ForSession(ctx context.Context, gameSessionID string) ([]ConnectionRecord, error) {
	var records []ConnectionRecord
	var startKey map[string]types.AttributeValue
	for {
		out, err := c.db.Query(ctx, &dynamodb.QueryInput{
			TableName:                 &c.table,
			IndexName:                 strPtr("game_session_id-index"),
			KeyConditionExpression:    strPtr("game_session_id = :gid"),
			ExpressionAttributeValues: map[string]types.AttributeValue{":gid": &types.AttributeValueMemberS{Value: gameSessionID}},
			ExclusiveStartKey:         startKey,
		})
		if err != nil {
			return nil, fmt.Errorf("query connections for session %s: %w", gameSessionID, err)
		}
		batch, err := decodeConnections(out.Items)
		if err != nil {
			return nil, err
		}
		records = append(records, batch...)
		if len(out.LastEvaluatedKey) == 0 {
			return records, nil
		}
		startKey = out.LastEvaluatedKey
	}
}

// All scans every connection. Used by the admin cleanup.
func (c *Connections) All(ctx context.Context) ([]ConnectionRecord, error) {
	var records []ConnectionRecord
	var startKey map[string]types.AttributeValue
	for {
		out, err := c.db.Scan(ctx, &dynamodb.ScanInput{TableName: &c.table, ExclusiveStartKey: startKey})
		if err != nil {
			return nil, fmt.Errorf("scan connections: %w", err)
		}
		batch, err := decodeConnections(out.Items)
		if err != nil {
			return nil, err
		}
		records = append(records, batch...)
		if len(out.LastEvaluatedKey) == 0 {
			return records, nil
		}
		startKey = out.LastEvaluatedKey
	}
}

func decodeConnections(items []map[string]types.AttributeValue) ([]ConnectionRecord, error) {
	records := make([]ConnectionRecord, 0, len(items))
	for _, item := range items {
		var record ConnectionRecord
		if err := decodeItem(item, &record); err != nil {
			return nil, fmt.Errorf("decode connection: %w", err)
		}
		records = append(records, record)
	}
	return records, nil
}
