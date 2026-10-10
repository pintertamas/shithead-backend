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

// janitorGracePeriod is how long a game (a started game or a lobby still waiting
// for players) must have been idle, with no save, before the janitor may treat it
// as abandoned. Every save refreshes updated_at, so a game that is being played or
// joined is never idle this long.
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

// runJanitor deletes abandoned games. A game is deleted only when it is unfinished,
// either a started game or a lobby still waiting for players, idle past the grace
// period and has no live WebSocket connection (see hasLiveConnection). A connection
// row past its ttl is not live, so a socket open longer than an hour counts as gone;
// games expire an hour after creation anyway. Finished games are never touched.
func (a *App) runJanitor(ctx context.Context) (JanitorResult, error) {
	var result JanitorResult
	var startKey map[string]types.AttributeValue
	now := a.now()
	checker := newConnectionChecker(ctx)
	for {
		out, err := a.db.Scan(ctx, &dynamodb.ScanInput{
			TableName:                 &a.settings.GameSessionsTable,
			ProjectionExpression:      strPtr("#gid, #started, #finished, #updated, #created, #ttl"),
			FilterExpression:          strPtr("#finished = :false"),
			ExpressionAttributeNames:  gameAttributeNames(),
			ExpressionAttributeValues: map[string]types.AttributeValue{":false": boolValue(false)},
			ExclusiveStartKey:         startKey,
		})
		if err != nil {
			return result, fmt.Errorf("scan games: %w", err)
		}
		for _, item := range out.Items {
			result.Scanned++
			a.janitorConsider(ctx, item, now, checker, &result)
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
func (a *App) janitorConsider(ctx context.Context, item map[string]types.AttributeValue, now time.Time, checker connectionChecker, result *JanitorResult) {
	gameID := stringAttr(item, "game_id")
	// The scan filter already checks finished; re-checking keeps the rule local and safe.
	if gameID == "" || boolAttr(item, "finished") {
		return
	}
	started, ok := startedFlag(item)
	if !ok {
		log.Printf("janitor skipped game %s: started flag is not a boolean", gameID)
		result.Skipped++
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
	live, err := a.hasLiveConnection(ctx, gameID, now, checker)
	if err != nil {
		log.Printf("janitor skipped game %s: %v", gameID, err)
		result.Skipped++
		return
	}
	if live {
		result.Kept++
		return
	}
	deleted, err := a.deleteIfStillIdle(ctx, item, gameID, started)
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
	kind := "game"
	if !started {
		kind = "lobby"
	}
	log.Printf("janitor deleted abandoned %s %s, idle %ds", kind, gameID, int64(idle.Seconds()))
}

// startedFlag reads the started attribute. ok is false unless it is stored as a
// BOOL, so an item with a missing or malformed flag is never treated as a lobby.
func startedFlag(item map[string]types.AttributeValue) (started, ok bool) {
	flag, isBool := item["started"].(*types.AttributeValueMemberBOOL)
	if !isBool {
		return false, false
	}
	return flag.Value, true
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

// hasLiveConnection reports whether the game has a live WebSocket connection. Only
// rows whose ttl is still in the future are candidates; a row without a ttl counts
// as one. Every candidate is confirmed with API Gateway: an open connection keeps the
// game, a gone one (HTTP 410) has its row deleted and does not count, and any other
// check failure keeps the game. Every candidate is checked, so stale rows are cleaned
// up even when another connection is open. A query failure is returned as an error.
func (a *App) hasLiveConnection(ctx context.Context, gameID string, now time.Time, checker connectionChecker) (bool, error) {
	var startKey map[string]types.AttributeValue
	live := false
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
			if !connectionIsLive(row, now) {
				continue
			}
			connectionID := stringAttr(row, "connection_id")
			state, err := checker.check(ctx, connectionID)
			if err != nil {
				log.Printf("janitor keeps game %s: connection check failed: %v", gameID, err)
				return true, nil
			}
			if state == connectionOpen {
				live = true
				continue
			}
			a.removeStaleConnection(ctx, gameID, connectionID)
		}
		if out.LastEvaluatedKey == nil {
			return live, nil
		}
		startKey = out.LastEvaluatedKey
	}
}

// removeStaleConnection deletes the row of a connection API Gateway reports gone.
// A failed delete is logged only: the connection is gone either way.
func (a *App) removeStaleConnection(ctx context.Context, gameID, connectionID string) {
	_, err := a.db.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName: &a.settings.ConnectionsTable,
		Key:       key("connection_id", connectionID),
	})
	if err != nil {
		log.Printf("janitor could not delete stale connection %s of game %s: %v", connectionID, gameID, err)
		return
	}
	log.Printf("janitor removed stale connection %s of game %s", connectionID, gameID)
}

// connectionIsLive reports whether a connection row's ttl is still in the future.
// A row without a ttl is treated as live, so it is checked with API Gateway.
func connectionIsLive(row map[string]types.AttributeValue, now time.Time) bool {
	ttl, ok := numberAttr(row, "ttl")
	return !ok || ttl > now.Unix()
}

// deleteIfStillIdle deletes the game only if it still has the started value it was
// scanned with, is unfinished and is unchanged since the scan. It reports false
// when the condition failed (a save happened in between). The request names only
// the attributes its condition uses: DynamoDB rejects unused expression names.
func (a *App) deleteIfStillIdle(ctx context.Context, item map[string]types.AttributeValue, gameID string, started bool) (bool, error) {
	names := map[string]string{"#started": "started", "#finished": "finished", "#updated": "updated_at"}
	values := map[string]types.AttributeValue{":started": boolValue(started), ":false": boolValue(false)}
	condition := "#started = :started AND #finished = :false AND attribute_not_exists(#updated)"
	if seen, ok := item["updated_at"]; ok {
		condition = "#started = :started AND #finished = :false AND (attribute_not_exists(#updated) OR #updated <= :seen)"
		values[":seen"] = seen
	}
	_, err := a.db.DeleteItem(ctx, &dynamodb.DeleteItemInput{
		TableName:                 &a.settings.GameSessionsTable,
		Key:                       key("game_id", gameID),
		ConditionExpression:       &condition,
		ExpressionAttributeNames:  names,
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
