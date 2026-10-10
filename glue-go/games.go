package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

const (
	sessionTTLSeconds   = 3600
	maxSessionCodeTries = 10
	unknownUsername     = "Unknown"
)

// createGame handles POST /create-game for the Cognito-authorized owner.
func (a *App) createGame(ctx context.Context, raw json.RawMessage) (any, error) {
	var req events.APIGatewayProxyRequest
	if err := json.Unmarshal(raw, &req); err != nil {
		return jsonResponse(400, map[string]string{"error": "Invalid request"})
	}
	userID := claimsSub(asMap(req.RequestContext.Authorizer))
	if userID == "" {
		return jsonResponse(401, map[string]string{"message": "Unauthorized"})
	}

	body := map[string]any{}
	if strings.TrimSpace(req.Body) != "" {
		if err := json.Unmarshal([]byte(req.Body), &body); err != nil || body == nil {
			return jsonResponse(400, map[string]string{"error": "Invalid JSON body"})
		}
	}
	config, err := parseGameConfig(body)
	if err != nil {
		return jsonResponse(400, map[string]string{"error": "Invalid game configuration"})
	}
	if config.VoiceEnabled && !isGameAdmin(asMap(req.RequestContext.Authorizer)) {
		return jsonResponse(403, map[string]string{"message": "Only administrators can enable voice chat."})
	}
	if config.VoiceEnabled {
		paused, err := a.voiceCreationPaused(ctx)
		if err != nil {
			return nil, err
		}
		if paused {
			return jsonResponse(409, map[string]string{"message": voicePausedMessage})
		}
	}

	if err := a.cleanupOldSessions(ctx, userID); err != nil {
		return nil, err
	}
	gameID, err := a.newGameID(ctx)
	if err != nil {
		return nil, err
	}
	username, err := a.usernameFor(ctx, userID)
	if err != nil {
		return nil, err
	}

	item := map[string]any{
		"game_id": gameID,
		"user_id": userID,
		"players": []any{map[string]any{
			"playerId": userID,
			"username": username,
			"hand":     []any{},
			"faceUp":   []any{},
			"faceDown": []any{},
			"out":      false,
		}},
		"discardPile":     []any{},
		"deck":            []any{},
		"currentPlayerId": userID,
		"started":         false,
		"finished":        false,
		"shitheadId":      nil,
		"config":          config,
		"created_at":      a.now().UTC().Format(pythonISOFormat),
		"updated_at":      a.now().Unix(),
		"ttl":             a.now().Unix() + sessionTTLSeconds,
	}
	if err := a.putItem(ctx, a.settings.GameSessionsTable, item); err != nil {
		return nil, err
	}
	return jsonResponse(200, map[string]string{"sessionId": gameID})
}

// gameAdminGroup is the Cognito group that may enable voice chat for a game.
const gameAdminGroup = "game-admin"

// isGameAdmin mirrors AccountManagementFunctionConfig.hasAdminGroup: cognito:groups
// may be a JSON array or a bracketed string such as "[game-admin other]".
func isGameAdmin(authorizer map[string]any) bool {
	claims, ok := authorizer["claims"].(map[string]any)
	if !ok {
		return false
	}
	switch groups := claims["cognito:groups"].(type) {
	case []any:
		for _, group := range groups {
			if name, _ := group.(string); name == gameAdminGroup {
				return true
			}
		}
	case string:
		normalized := strings.NewReplacer("[", "", "]", "").Replace(groups)
		for _, name := range strings.FieldsFunc(normalized, func(r rune) bool { return r == ',' || r == ' ' }) {
			if name == gameAdminGroup {
				return true
			}
		}
	}
	return false
}

// asMap converts an authorizer context value into a map, or an empty map.
func asMap(value any) map[string]any {
	m, _ := value.(map[string]any)
	if m == nil {
		return map[string]any{}
	}
	return m
}

// pythonISOFormat matches datetime.isoformat() in UTC, which the Python version wrote.
const pythonISOFormat = "2006-01-02T15:04:05.000000+00:00"

// usernameFor looks up the display name of the owner, defaulting to "Unknown".
func (a *App) usernameFor(ctx context.Context, userID string) (string, error) {
	item, err := a.getItem(ctx, a.settings.UsersTable, "user_id", userID)
	if err != nil {
		return "", err
	}
	if name := stringAttr(item, "username"); name != "" {
		return name, nil
	}
	return unknownUsername, nil
}

// newGameID tries a few random codes, preferring one not already in use.
func (a *App) newGameID(ctx context.Context) (string, error) {
	var code string
	for range maxSessionCodeTries {
		candidate, err := a.newCode()
		if err != nil {
			return "", err
		}
		code = candidate
		existing, err := a.getItem(ctx, a.settings.GameSessionsTable, "game_id", candidate)
		if err != nil {
			return "", err
		}
		if existing == nil {
			return candidate, nil
		}
	}
	return code, nil
}

// cleanupOldSessions removes the owner from unstarted lobbies they own. A lobby
// with no players left is deleted; otherwise the next player becomes owner.
func (a *App) cleanupOldSessions(ctx context.Context, userID string) error {
	out, err := a.db.Query(ctx, &dynamodb.QueryInput{
		TableName:                 &a.settings.GameSessionsTable,
		IndexName:                 strPtr("user_id-index"),
		KeyConditionExpression:    strPtr("user_id = :uid"),
		ExpressionAttributeValues: map[string]types.AttributeValue{":uid": s(userID)},
	})
	if err != nil {
		return fmt.Errorf("query owned sessions: %w", err)
	}
	for _, item := range out.Items {
		if boolAttr(item, "started") {
			continue
		}
		if err := a.handOverOrDelete(ctx, item, userID); err != nil {
			return err
		}
	}
	return nil
}

func (a *App) handOverOrDelete(ctx context.Context, item map[string]types.AttributeValue, userID string) error {
	gameID := stringAttr(item, "game_id")
	remaining := remainingPlayers(item, userID)
	if len(remaining) == 0 {
		_, err := a.db.DeleteItem(ctx, &dynamodb.DeleteItemInput{
			TableName: &a.settings.GameSessionsTable,
			Key:       key("game_id", gameID),
		})
		if err != nil {
			return fmt.Errorf("delete lobby %s: %w", gameID, err)
		}
		return nil
	}
	newOwner := playerIDOf(remaining[0])
	_, err := a.db.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName:                 &a.settings.GameSessionsTable,
		Key:                       key("game_id", gameID),
		UpdateExpression:          strPtr("SET players = :p, user_id = :o"),
		ExpressionAttributeValues: map[string]types.AttributeValue{":p": &types.AttributeValueMemberL{Value: remaining}, ":o": s(newOwner)},
	})
	if err != nil {
		return fmt.Errorf("hand over lobby %s: %w", gameID, err)
	}
	return nil
}

// remainingPlayers returns the players of a lobby item except userID.
func remainingPlayers(item map[string]types.AttributeValue, userID string) []types.AttributeValue {
	list, _ := item["players"].(*types.AttributeValueMemberL)
	var remaining []types.AttributeValue
	if list == nil {
		return remaining
	}
	for _, player := range list.Value {
		if playerIDOf(player) != userID {
			remaining = append(remaining, player)
		}
	}
	return remaining
}

// playerIDOf reads playerId from a stored player map.
func playerIDOf(player types.AttributeValue) string {
	m, ok := player.(*types.AttributeValueMemberM)
	if !ok {
		return ""
	}
	return stringAttr(m.Value, "playerId")
}

func strPtr(value string) *string {
	return &value
}

// marshalItem converts a Go map into a DynamoDB item.
func marshalItem(item map[string]any) (map[string]types.AttributeValue, error) {
	av, err := attributevalue.MarshalMap(item)
	if err != nil {
		return nil, fmt.Errorf("marshal item: %w", err)
	}
	return av, nil
}
