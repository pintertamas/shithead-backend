package main

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

func numberValue(value int64) types.AttributeValue {
	return &types.AttributeValueMemberN{Value: strconv.FormatInt(value, 10)}
}

// idleGame is a started, unfinished game last saved `idle` ago.
func idleGame(id string, idle time.Duration) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{
		"game_id":    s(id),
		"started":    boolValue(true),
		"finished":   boolValue(false),
		"updated_at": numberValue(testNow.Add(-idle).Unix()),
		"ttl":        numberValue(testNow.Unix() + sessionTTLSeconds),
	}
}

func connectionRow(connectionID, gameID string, ttlOffset time.Duration) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{
		"connection_id":   s(connectionID),
		"game_session_id": s(gameID),
		"ttl":             numberValue(testNow.Add(ttlOffset).Unix()),
	}
}

func withoutAttribute(item map[string]types.AttributeValue, name string) map[string]types.AttributeValue {
	delete(item, name)
	return item
}

func runJanitorForTest(t *testing.T, app *App) JanitorResult {
	t.Helper()
	result, err := app.runJanitor(context.Background())
	if err != nil {
		t.Fatalf("runJanitor: %v", err)
	}
	return result
}

func TestJanitorDeletesAbandonedStartedGame(t *testing.T) {
	// Given: a started game idle for 20 minutes with no connection
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-dead", 20*time.Minute))
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then
	if db.item(testGames, "g-dead") != nil {
		t.Fatal("abandoned game was not deleted")
	}
	if result.Deleted != 1 {
		t.Fatalf("Deleted = %d, want 1", result.Deleted)
	}
}

func TestJanitorKeepsGameWithLiveConnection(t *testing.T) {
	// Given: an idle started game that still has a live connection
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-watched", 40*time.Minute))
	db.seed(testConnections, connectionRow("c1", "g-watched", time.Hour))
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then
	if db.item(testGames, "g-watched") == nil {
		t.Fatal("game with a live connection was deleted")
	}
	if result.Kept != 1 || result.Deleted != 0 {
		t.Fatalf("result = %+v, want one kept game", result)
	}
}

func TestJanitorKeepsGameUpdatedWithinGracePeriod(t *testing.T) {
	// Given: a started game saved 5 minutes ago with no connection
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-active", 5*time.Minute))
	app := newTestApp(db)

	// When
	runJanitorForTest(t, app)

	// Then
	if db.item(testGames, "g-active") == nil {
		t.Fatal("game inside the grace period was deleted")
	}
}

func TestJanitorKeepsUnstartedLobbiesAndFinishedGames(t *testing.T) {
	// Given: an unstarted lobby and a finished game, both long idle with no connection
	db := newFakeDynamo()
	lobby := idleGame("g-lobby", 3*time.Hour)
	lobby["started"] = boolValue(false)
	finished := idleGame("g-done", 3*time.Hour)
	finished["finished"] = boolValue(true)
	db.seed(testGames, lobby)
	db.seed(testGames, finished)
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then
	if db.item(testGames, "g-lobby") == nil || db.item(testGames, "g-done") == nil {
		t.Fatal("an unstarted lobby or a finished game was deleted")
	}
	if result.Deleted != 0 {
		t.Fatalf("Deleted = %d, want 0", result.Deleted)
	}
}

func TestJanitorFallsBackToCreatedAtWhenUpdatedAtMissing(t *testing.T) {
	// Given: an old game without updated_at (created 2 hours ago)
	db := newFakeDynamo()
	old := withoutAttribute(idleGame("g-old", 0), "updated_at")
	old["created_at"] = s(testNow.Add(-2 * time.Hour).UTC().Format(pythonISOFormat))
	db.seed(testGames, old)
	// Given: a game with neither timestamp, whose TTL implies creation 2 hours ago
	noStamps := withoutAttribute(idleGame("g-ttl-only", 0), "updated_at")
	noStamps["ttl"] = numberValue(testNow.Add(-2*time.Hour).Unix() + sessionTTLSeconds)
	db.seed(testGames, noStamps)
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then
	if db.item(testGames, "g-old") != nil || db.item(testGames, "g-ttl-only") != nil {
		t.Fatal("games with only an old creation time were not deleted")
	}
	if result.Deleted != 2 {
		t.Fatalf("Deleted = %d, want 2", result.Deleted)
	}
}

func TestJanitorKeepsGameWhenConnectionQueryFails(t *testing.T) {
	// Given: an abandoned-looking game, but the connection query errors
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-unsure", 30*time.Minute))
	db.queryErr = errors.New("throttled")
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then: the game is kept and the run itself succeeds
	if db.item(testGames, "g-unsure") == nil {
		t.Fatal("game was deleted although its connections could not be checked")
	}
	if result.Skipped != 1 {
		t.Fatalf("Skipped = %d, want 1", result.Skipped)
	}
}

func TestJanitorConditionalDeleteLostRaceIsNotAnError(t *testing.T) {
	// Given: an idle game, but a player saves it between the scan and the delete
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-race", 20*time.Minute))
	db.onQuery = func() {
		db.onQuery = nil
		db.seed(testGames, idleGame("g-race", time.Minute))
	}
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then: the freshly saved game survives and the lost race is counted as kept
	if db.item(testGames, "g-race") == nil {
		t.Fatal("game saved during the run was deleted")
	}
	if result.Kept != 1 || result.Skipped != 0 || result.Deleted != 0 {
		t.Fatalf("result = %+v, want one kept game and no errors", result)
	}
}

func TestJanitorIgnoresExpiredConnectionRows(t *testing.T) {
	// Given: an idle game whose only connection row has already expired (TTL deletion lags)
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-expired-conn", 20*time.Minute))
	db.seed(testConnections, connectionRow("stale", "g-expired-conn", -time.Second))
	app := newTestApp(db)

	// When
	runJanitorForTest(t, app)

	// Then
	if db.item(testGames, "g-expired-conn") != nil {
		t.Fatal("game was kept because of a connection row whose ttl had passed")
	}
}

func TestHandleRunsJanitorForScheduledEvent(t *testing.T) {
	// Given: an abandoned game and an EventBridge scheduled event
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-scheduled", 20*time.Minute))
	app := newTestApp(db)
	event := json.RawMessage(`{"source":"aws.events","detail-type":"Scheduled Event","detail":{}}`)

	// When
	if _, err := app.Handle(context.Background(), event); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	// Then
	if db.item(testGames, "g-scheduled") != nil {
		t.Fatal("scheduled event did not run the janitor")
	}
}

func TestCreateGameStampsUpdatedAt(t *testing.T) {
	// Given
	db := newFakeDynamo()
	app := newTestApp(db, "NEWGME")

	// When
	if _, err := app.Handle(context.Background(), mustRaw(t, restCreateGame("owner-1", `{}`))); err != nil {
		t.Fatalf("Handle: %v", err)
	}

	// Then
	item := db.item(testGames, "NEWGME")
	if got, _ := numberAttr(item, "updated_at"); got != testNow.Unix() {
		t.Fatalf("updated_at = %d, want %d", got, testNow.Unix())
	}
}
