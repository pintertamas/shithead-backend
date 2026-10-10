package main

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

const (
	// voiceMonthlyLimitMinutes is the LiveKit free plan allowance in participant-minutes.
	// Reaching it deletes every LiveKit room (see voice_roomservice.go).
	voiceMonthlyLimitMinutes = 5000
	// voiceGuardMinutes is the usage from which new games with voice are refused:
	// about 10 percent of the allowance is left.
	voiceGuardMinutes = 4500
	// voicePausedMessage is the 409 text of a refused create-game. It must not contain quotes.
	voicePausedMessage = "Voice chat is paused until next month to stay within the free LiveKit allowance."

	// Both counter kinds live in the users table next to the profiles, under the
	// partition key user_id. Their prefixes keep them apart from real user ids.
	voiceUsagePrefix = "__voice_usage#"
	voiceOpenPrefix  = "__voice_open#"
)

// voiceUsageKey is the item that holds the participant-minutes of one UTC month.
func voiceUsageKey(at time.Time) string {
	return voiceUsagePrefix + at.UTC().Format("2006-01")
}

// voiceOpenKey is the item of one participant's open voice connection.
func voiceOpenKey(room, identity string) string {
	return voiceOpenPrefix + room + "#" + identity
}

// voiceMinutesUsed returns the participant-minutes recorded for the UTC month of at.
func (a *App) voiceMinutesUsed(ctx context.Context, at time.Time) (int64, error) {
	item, err := a.getItem(ctx, a.settings.UsersTable, "user_id", voiceUsageKey(at))
	if err != nil {
		return 0, err
	}
	minutes, _ := numberAttr(item, "minutes")
	return minutes, nil
}

// voiceCreationPaused reports whether this month's usage has reached the guard, so
// that games with voice enabled must not be created.
func (a *App) voiceCreationPaused(ctx context.Context) (bool, error) {
	used, err := a.voiceMinutesUsed(ctx, a.now())
	if err != nil {
		return false, err
	}
	return used >= voiceGuardMinutes, nil
}

// addVoiceMinutes adds minutes to the UTC month of at in one atomic update and
// returns the month's new total.
func (a *App) addVoiceMinutes(ctx context.Context, at time.Time, minutes int64) (int64, error) {
	out, err := a.db.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName:                 &a.settings.UsersTable,
		Key:                       key("user_id", voiceUsageKey(at)),
		UpdateExpression:          strPtr("ADD #minutes :minutes"),
		ExpressionAttributeNames:  map[string]string{"#minutes": "minutes"},
		ExpressionAttributeValues: map[string]types.AttributeValue{":minutes": &types.AttributeValueMemberN{Value: strconv.FormatInt(minutes, 10)}},
		ReturnValues:              types.ReturnValueUpdatedNew,
	})
	if err != nil {
		return 0, fmt.Errorf("add voice minutes: %w", err)
	}
	total, _ := numberAttr(out.Attributes, "minutes")
	return total, nil
}

// openVoiceSession records a join. It overwrites any older row for the same
// participant, so a missed leave event cannot be counted twice for a new connection.
func (a *App) openVoiceSession(ctx context.Context, room, identity string, joinedAt int64) error {
	return a.putItem(ctx, a.settings.UsersTable, map[string]any{
		"user_id":   voiceOpenKey(room, identity),
		"joined_at": joinedAt,
	})
}

// recordVoiceLeave removes the open row. When one came back, its connection is
// counted in the month of the leave event and the new total is returned with true.
// A duplicate or unmatched leave finds no row and counts nothing.
func (a *App) recordVoiceLeave(ctx context.Context, room, identity string, leftAt int64) (int64, bool, error) {
	out, err := a.db.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName:    &a.settings.UsersTable,
		Key:          key("user_id", voiceOpenKey(room, identity)),
		ReturnValues: types.ReturnValueAllOld,
	})
	if err != nil {
		return 0, false, fmt.Errorf("close voice session: %w", err)
	}
	joinedAt, ok := numberAttr(out.Attributes, "joined_at")
	if !ok {
		return 0, false, nil
	}
	total, err := a.addVoiceMinutes(ctx, time.Unix(leftAt, 0), connectedMinutes(joinedAt, leftAt))
	if err != nil {
		return 0, false, err
	}
	return total, true, nil
}

// connectedMinutes is the LiveKit billing rule: each connection rounds up to a whole
// minute, and a connection always costs at least one minute.
func connectedMinutes(joinedAt, leftAt int64) int64 {
	elapsed := leftAt - joinedAt
	if elapsed <= 0 {
		return 1
	}
	return (elapsed + 59) / 60
}
