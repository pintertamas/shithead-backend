package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

const (
	defaultLeaderboardPartition = "global"
	defaultEloScore             = "1000"
)

var errMissingSub = errors.New("cognito event has no sub attribute")

// cognitoTrigger is the part of a Cognito post-confirmation or
// post-authentication event that init-user reads.
type cognitoTrigger struct {
	TriggerSource string `json:"triggerSource"`
	Request       struct {
		UserAttributes map[string]string `json:"userAttributes"`
	} `json:"request"`
}

// initUser seeds a user profile on first sign-in. Every attribute is written
// with if_not_exists so a display name the user has edited is never overwritten.
// The Cognito event is returned unchanged, as the trigger contract requires.
func (a *App) initUser(ctx context.Context, raw json.RawMessage) (json.RawMessage, error) {
	var trigger cognitoTrigger
	if err := json.Unmarshal(raw, &trigger); err != nil {
		return nil, fmt.Errorf("decode cognito event: %w", err)
	}
	attrs := trigger.Request.UserAttributes
	userID := attrs["sub"]
	if userID == "" {
		return nil, errMissingSub
	}
	username := attrs["preferred_username"]
	if username == "" {
		username = attrs["email"]
	}
	log.Printf("init_user triggered for user_id=%s, trigger=%s", userID, trigger.TriggerSource)

	if err := a.seedUser(ctx, userID, username); err != nil {
		log.Printf("init_user failed for user_id=%s: %v", userID, err)
		return nil, err
	}
	return raw, nil
}

func (a *App) seedUser(ctx context.Context, userID, username string) error {
	usernameValue := types.AttributeValue(&types.AttributeValueMemberNULL{Value: true})
	if username != "" {
		usernameValue = s(username)
	}
	_, err := a.db.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName: &a.settings.UsersTable,
		Key:       key("user_id", userID),
		UpdateExpression: strPtr("SET username = if_not_exists(username, :u), " +
			"leaderboard_pk = if_not_exists(leaderboard_pk, :lpk), " +
			"elo_score = if_not_exists(elo_score, :elo), " +
			"created_at = if_not_exists(created_at, :ca)"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":u":   usernameValue,
			":lpk": s(defaultLeaderboardPartition),
			":elo": &types.AttributeValueMemberN{Value: defaultEloScore},
			":ca":  s(a.now().UTC().Format(pythonISOFormat)),
		},
	})
	if err != nil {
		return fmt.Errorf("seed user %s: %w", userID, err)
	}
	return nil
}
