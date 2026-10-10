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

// initUser seeds a user profile on sign-in. The seed attributes are written
// with if_not_exists so a display name the user has edited is never overwritten.
// The email is set whenever the event carries one, and kept otherwise.
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
	email := attrs["email"]
	log.Printf("init_user triggered for user_id=%s, trigger=%s", userID, trigger.TriggerSource)

	if err := a.seedUser(ctx, userID, username, email); err != nil {
		log.Printf("init_user failed for user_id=%s: %v", userID, err)
		return nil, err
	}
	return raw, nil
}

// seedUser writes the profile row. The email clause is added only when the
// token carries an email, so a row keeps its stored email when the claim is absent.
func (a *App) seedUser(ctx context.Context, userID, username, email string) error {
	usernameValue := types.AttributeValue(&types.AttributeValueMemberNULL{Value: true})
	if username != "" {
		usernameValue = s(username)
	}
	values := map[string]types.AttributeValue{
		":u":   usernameValue,
		":lpk": s(defaultLeaderboardPartition),
		":elo": &types.AttributeValueMemberN{Value: defaultEloScore},
		":ca":  s(a.now().UTC().Format(pythonISOFormat)),
	}
	updateExpression := "SET username = if_not_exists(username, :u), " +
		"leaderboard_pk = if_not_exists(leaderboard_pk, :lpk), " +
		"elo_score = if_not_exists(elo_score, :elo), " +
		"created_at = if_not_exists(created_at, :ca)"
	if email != "" {
		updateExpression += ", email = :email"
		values[":email"] = s(email)
	}
	_, err := a.db.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName:                 &a.settings.UsersTable,
		Key:                       key("user_id", userID),
		UpdateExpression:          strPtr(updateExpression),
		ExpressionAttributeValues: values,
	})
	if err != nil {
		return fmt.Errorf("seed user %s: %w", userID, err)
	}
	return nil
}
