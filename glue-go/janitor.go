package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strconv"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// janitorGracePeriod is how long a started game must have been idle (no save)
// before the janitor may treat it as abandoned. Every save refreshes updated_at,
// so a game that is being played is never idle this long.
const janitorGracePeriod = 15 * time.Minute

const connectionsGameIndex = "game_session_id-index"

// JanitorResult summarises one scheduled run.
type JanitorResult struct {
	Scanned int `json:"scanned"`
	Deleted int `json:"deleted"`
	Kept    int `json:"kept"`
	Skipped int `json:"skipped"`
}

// isScheduledEvent reports whether the probe is an EventBridge scheduled event.
func isScheduledEvent(probe eventProbe) bool {
	return probe.Source == "aws.events" && probe.DetailType == "Scheduled Event"
}

// runJanitor deletes abandoned in-progress games. A game is deleted only when it
// is started, unfinished, idle past the grace period and has no live WebSocket
// connection registered. Lobbies and finished games are never touched.
func (a *App) runJanitor(ctx context.Context) (JanitorResult, error) {
	var result JanitorResult
	var startKey map[string]types.AttributeValue
	now := a.now()
	for {
		out, err := a.db.Scan(ctx, &dynamodb.ScanInput{
			TableName:                 &a.settings.GameSessionsTable,
			ProjectionExpression:      strPtr("#gid, #started, #finished, #updated, #created, #ttl"),
			FilterExpression:          strPtr("#started = :true AND #finished = :false"),
			ExpressionAttributeNames:  gameAttributeNames(),
			ExpressionAttributeValues: map[string]types.AttributeValue{":true": boolValue(true), ":false": boolValue(false)},
			ExclusiveStartKey:         startKey,
		})
		if err != nil {
			return result, fmt.Errorf("scan games: %w", err)
		}
		for _, item := range out.Items {
			result.Scanned++
			a.janitorConsider(ctx, item, now, &result)
		}
		if out.LastEvaluatedKey == nil {
			break
		}
		startKey = out.LastEvaluatedKey
	}
	log.Printf("janitor summary: scanned=%d deleted=%d kept=%d skipped=%d",
		result.Scanned, result.Deleted, result.Kept, result.Skipped)
	return result, nil
}

// janitorConsider applies every rule to one scanned game. Errors are logged and
// the game is skipped: the janitor never deletes when it is uncertain.
func (a *App) janitorConsider(ctx context.Context, item map[string]types.AttributeValue, now time.Time, result *JanitorResult) {
	gameID := stringAttr(item, "game_id")
	// The scan filter already checks these; re-checking keeps the rule local and safe.
	if gameID == "" || !boolAttr(item, "started") || boolAttr(item, "finished") {
		return
	}
	lastSeen, ok := lastActivity(item)
	if !ok {
		log.Printf("janitor skipped game %s: no activity timestamp", gameID)
		result.Skipped++
		return
	}
	idle := now.Sub(lastSeen)
	if idle < janitorGracePeriod {
		result.Kept++
		return
	}
	live, err := a.hasLiveConnection(ctx, gameID, now)
	if err != nil {
		log.Printf("janitor skipped game %s: %v", gameID, err)
		result.Skipped++
		return
	}
	if live {
		result.Kept++
		return
	}
	deleted, err := a.deleteIfStillIdle(ctx, item, gameID)
	if err != nil {
		log.Printf("janitor skipped game %s: %v", gameID, err)
		result.Skipped++
		return
	}
	if !deleted {
		// Saved or changed after the scan: a lost race, not an error.
		result.Kept++
		return
	}
	result.Deleted++
	log.Printf("janitor deleted abandoned game %s, idle %ds", gameID, int64(idle.Seconds()))
}

// lastActivity returns when the game was last saved: updated_at, else the
// created_at timestamp, else the creation time implied by the TTL.
func lastActivity(item map[string]types.AttributeValue) (time.Time, bool) {
	if epoch, ok := numberAttr(item, "updated_at"); ok {
		return time.Unix(epoch, 0), true
	}
	if created, err := time.Parse(time.RFC3339Nano, stringAttr(item, "created_at")); err == nil {
		return created, true
	}
	if ttl, ok := numberAttr(item, "ttl"); ok {
		return time.Unix(ttl-sessionTTLSeconds, 0), true
	}
	return time.Time{}, false
}

// hasLiveConnection reports whether any connection registered for the game has
// not expired. Rows whose own ttl is already past are ignored, because DynamoDB
// TTL deletion can lag. A row without a ttl counts as live.
func (a *App) hasLiveConnection(ctx context.Context, gameID string, now time.Time) (bool, error) {
	var startKey map[string]types.AttributeValue
	for {
		out, err := a.db.Query(ctx, &dynamodb.QueryInput{
			TableName:                 &a.settings.ConnectionsTable,
			IndexName:                 strPtr(connectionsGameIndex),
			KeyConditionExpression:    strPtr("#gsid = :game"),
			ProjectionExpression:      strPtr("#cid, #ttl"),
			ExpressionAttributeNames:  map[string]string{"#gsid": "game_session_id", "#cid": "connection_id", "#ttl": "ttl"},
			ExpressionAttributeValues: map[string]types.AttributeValue{":game": s(gameID)},
			ExclusiveStartKey:         startKey,
		})
		if err != nil {
			return false, fmt.Errorf("query connections: %w", err)
		}
		for _, row := range out.Items {
			if connectionIsLive(row, now) {
				return true, nil
			}
		}
		if out.LastEvaluatedKey == nil {
			return false, nil
		}
		startKey = out.LastEvaluatedKey
	}
}

func connectionIsLive(row map[string]types.AttributeValue, now time.Time) bool {
	ttl, ok := numberAttr(row, "ttl")
	return !ok || ttl > now.Unix()
}

// deleteIfStillIdle deletes the game only if it is still started, unfinished and
// unchanged since the scan. It reports false when the condition failed (a save
// happened in between).
func (a *App) deleteIfStillIdle(ctx context.Context, item map[string]types.AttributeValue, gameID string) (bool, error) {
	values := map[string]types.AttributeValue{":true": boolValue(true), ":false": boolValue(false)}
	condition := "#started = :true AND #finished = :false AND attribute_not_exists(#updated)"
	if seen, ok := item["updated_at"]; ok {
		condition = "#started = :true AND #finished = :false AND (attribute_not_exists(#updated) OR #updated <= :seen)"
		values[":seen"] = seen
	}
	_, err := a.db.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName:                 &a.settings.GameSessionsTable,
		Key:                       key("game_id", gameID),
		ConditionExpression:       &condition,
		ExpressionAttributeNames:  gameAttributeNames(),
		ExpressionAttributeValues: values,
	})
	var failed *types.ConditionalCheckFailedException
	if errors.As(err, &failed) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("delete game: %w", err)
	}
	return true, nil
}

func gameAttributeNames() map[string]string {
	return map[string]string{
		"#gid":      "game_id",
		"#started":  "started",
		"#finished": "finished",
		"#updated":  "updated_at",
		"#created":  "created_at",
		"#ttl":      "ttl",
	}
}

// numberAttr reads an N attribute as int64.
func numberAttr(item map[string]types.AttributeValue, name string) (int64, bool) {
	v, ok := item[name].(*types.AttributeValueMemberN)
	if !ok {
		return 0, false
	}
	n, err := strconv.ParseInt(v.Value, 10, 64)
	return n, err == nil
}

func boolValue(value bool) types.AttributeValue {
	return &types.AttributeValueMemberBOOL{Value: value}
}
