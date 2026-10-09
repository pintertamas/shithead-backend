package store

import (
	"context"
	"fmt"

	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// Games is the repository for the game-sessions table (key game_id).
type Games struct {
	db    DynamoDB
	table string
}

// Get returns the game, or nil when it does not exist.
func (g *Games) Get(ctx context.Context, gameID string) (*GameRecord, error) {
	out, err := g.db.GetItem(ctx, &dynamodb.GetItemInput{
		TableName:      &g.table,
		Key:            strKey("game_id", gameID),
		ConsistentRead: boolPtr(true),
	})
	if err != nil {
		return nil, fmt.Errorf("get game %s: %w", gameID, err)
	}
	if len(out.Item) == 0 {
		return nil, nil
	}
	record, err := decodeGame(out.Item)
	if err != nil {
		return nil, err
	}
	return &record, nil
}

// Put writes the whole game item, replacing any previous version.
func (g *Games) Put(ctx context.Context, record GameRecord) error {
	item, err := attributevalue.MarshalMap(record)
	if err != nil {
		return fmt.Errorf("marshal game %s: %w", record.GameID, err)
	}
	if _, err := g.db.PutItem(ctx, &dynamodb.PutItemInput{TableName: &g.table, Item: item}); err != nil {
		return fmt.Errorf("put game %s: %w", record.GameID, err)
	}
	return nil
}

// Delete removes a game by id.
func (g *Games) Delete(ctx context.Context, gameID string) error {
	_, err := g.db.DeleteItem(ctx, &dynamodb.DeleteItemInput{TableName: &g.table, Key: strKey("game_id", gameID)})
	if err != nil {
		return fmt.Errorf("delete game %s: %w", gameID, err)
	}
	return nil
}

// ByOwner returns every game whose owner (user_id) is the given user.
func (g *Games) ByOwner(ctx context.Context, ownerID string) ([]GameRecord, error) {
	var records []GameRecord
	var startKey map[string]types.AttributeValue
	for {
		out, err := g.db.Query(ctx, &dynamodb.QueryInput{
			TableName:                 &g.table,
			IndexName:                 strPtr("user_id-index"),
			KeyConditionExpression:    strPtr("user_id = :uid"),
			ExpressionAttributeValues: map[string]types.AttributeValue{":uid": &types.AttributeValueMemberS{Value: ownerID}},
			ExclusiveStartKey:         startKey,
		})
		if err != nil {
			return nil, fmt.Errorf("query games by owner: %w", err)
		}
		batch, err := decodeGames(out.Items)
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

// All scans every game. Used by the admin cleanup and by profile renames.
func (g *Games) All(ctx context.Context) ([]GameRecord, error) {
	var records []GameRecord
	var startKey map[string]types.AttributeValue
	for {
		out, err := g.db.Scan(ctx, &dynamodb.ScanInput{TableName: &g.table, ExclusiveStartKey: startKey})
		if err != nil {
			return nil, fmt.Errorf("scan games: %w", err)
		}
		batch, err := decodeGames(out.Items)
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

func decodeGame(item map[string]types.AttributeValue) (GameRecord, error) {
	// A missing setupComplete attribute means true, as in the Java entity default.
	record := GameRecord{SetupComplete: true}
	if err := decodeItem(item, &record); err != nil {
		return GameRecord{}, fmt.Errorf("decode game: %w", err)
	}
	// A missing ready attribute means true, as in the Java entity default.
	for i := range record.Players {
		if record.Players[i].Ready == nil {
			ready := true
			record.Players[i].Ready = &ready
		}
	}
	return record, nil
}

func decodeGames(items []map[string]types.AttributeValue) ([]GameRecord, error) {
	records := make([]GameRecord, 0, len(items))
	for _, item := range items {
		record, err := decodeGame(item)
		if err != nil {
			return nil, err
		}
		records = append(records, record)
	}
	return records, nil
}

func boolPtr(v bool) *bool    { return &v }
func strPtr(v string) *string { return &v }
