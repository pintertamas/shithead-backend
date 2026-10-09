package main

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

const connectionTTLSeconds = 3600

// handleWebSocket routes the lifecycle routes. Gameplay routes (play, setup,
// pickup) never reach this function; API Gateway sends them to the Java Lambda.
func (a *App) handleWebSocket(ctx context.Context, raw json.RawMessage) (any, error) {
	var req events.APIGatewayWebsocketProxyRequest
	if err := json.Unmarshal(raw, &req); err != nil {
		return nil, fmt.Errorf("decode websocket event: %w", err)
	}
	switch req.RequestContext.RouteKey {
	case "$connect":
		return a.connect(ctx, req)
	case "$disconnect":
		return a.disconnect(ctx, req)
	case "$default":
		// Deliberately a no-op: clients must not be able to broadcast arbitrary
		// data to other players of a game through this route.
		return events.APIGatewayProxyResponse{StatusCode: 200, Body: "Ignored."}, nil
	default:
		return events.APIGatewayProxyResponse{StatusCode: 404, Body: "Unknown route."}, nil
	}
}

// connect records a new WebSocket connection with its game session and user.
func (a *App) connect(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) (any, error) {
	item := map[string]any{
		"connection_id": req.RequestContext.ConnectionID,
		"ttl":           a.now().Unix() + connectionTTLSeconds,
	}
	if gameSessionID := req.QueryStringParameters["game_session_id"]; gameSessionID != "" {
		item["game_session_id"] = gameSessionID
	}
	if userID, _ := asMap(req.RequestContext.Authorizer)["sub"].(string); userID != "" {
		item["user_id"] = userID
	}
	if err := a.putItem(ctx, a.settings.ConnectionsTable, item); err != nil {
		return nil, err
	}
	return events.APIGatewayProxyResponse{StatusCode: 200, Body: "Connected."}, nil
}

// disconnect forgets a WebSocket connection.
func (a *App) disconnect(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) (any, error) {
	_, err := a.db.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName: &a.settings.ConnectionsTable,
		Key:       key("connection_id", req.RequestContext.ConnectionID),
	})
	if err != nil {
		return nil, fmt.Errorf("delete connection: %w", err)
	}
	return events.APIGatewayProxyResponse{StatusCode: 200, Body: "Disconnected."}, nil
}
