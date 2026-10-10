package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
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

// lobbyGame is an unstarted, unfinished lobby last saved `idle` ago.
func lobbyGame(id string, idle time.Duration) map[string]types.AttributeValue {
	lobby := idleGame(id, idle)
	lobby["started"] = boolValue(false)
	return lobby
}

// strictDynamo rejects expression names and values that the request's expressions
// do not reference, as DynamoDB does with a ValidationException. The fake accepts
// them, so this wrapper is what catches a janitor request DynamoDB would refuse.
type strictDynamo struct {
	*fakeDynamo
	t *testing.T
}

func (s strictDynamo) Scan(ctx context.Context, in *dynamodb.ScanInput, opts ...func(*dynamodb.Options)) (*dynamodb.ScanOutput, error) {
	s.requireUsed(in.ExpressionAttributeNames, in.ExpressionAttributeValues, in.ProjectionExpression, in.FilterExpression)
	return s.fakeDynamo.Scan(ctx, in, opts...)
}

func (s strictDynamo) Query(ctx context.Context, in *dynamodb.QueryInput, opts ...func(*dynamodb.Options)) (*dynamodb.QueryOutput, error) {
	s.requireUsed(in.ExpressionAttributeNames, in.ExpressionAttributeValues, in.KeyConditionExpression, in.ProjectionExpression, in.FilterExpression)
	return s.fakeDynamo.Query(ctx, in, opts...)
}

func (s strictDynamo) DeleteItem(ctx context.Context, in *dynamodb.DeleteItemInput, opts ...func(*dynamodb.Options)) (*dynamodb.DeleteItemOutput, error) {
	s.requireUsed(in.ExpressionAttributeNames, in.ExpressionAttributeValues, in.ConditionExpression)
	return s.fakeDynamo.DeleteItem(ctx, in, opts...)
}

func (s strictDynamo) requireUsed(names map[string]string, values map[string]types.AttributeValue, expressions ...*string) {
	used := map[string]bool{}
	for _, expression := range expressions {
		if expression == nil {
			continue
		}
		for _, token := range strings.FieldsFunc(*expression, func(r rune) bool { return strings.ContainsRune(" (),=<>", r) }) {
			used[token] = true
		}
	}
	for name := range names {
		if !used[name] {
			s.t.Errorf("ExpressionAttributeNames %s is not used by any expression", name)
		}
	}
	for value := range values {
		if !used[value] {
			s.t.Errorf("ExpressionAttributeValues %s is not used by any expression", value)
		}
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
	// Given: an idle started game whose connection API Gateway reports open
	api := useManagementAPI(t, nil)
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
	if api.callCount() != 1 {
		t.Fatalf("GetConnection calls = %d, want 1", api.callCount())
	}
	if db.item(testConnections, "c1") == nil {
		t.Fatal("open connection row was removed")
	}
}

func TestJanitorDeletesGameWhoseConnectionIsGone(t *testing.T) {
	// Given: an idle started game whose only connection row is stale (API Gateway: gone)
	useManagementAPI(t, map[string]int{"c-stale": http.StatusGone})
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-gone", 40*time.Minute))
	db.seed(testConnections, connectionRow("c-stale", "g-gone", time.Hour))
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then: the stale row is removed and, with no other live connection, so is the game
	if db.item(testConnections, "c-stale") != nil {
		t.Fatal("stale connection row was not removed")
	}
	if db.item(testGames, "g-gone") != nil {
		t.Fatal("game whose only connection is gone was not deleted")
	}
	if result.Deleted != 1 {
		t.Fatalf("Deleted = %d, want 1", result.Deleted)
	}
}

func TestJanitorRemovesGoneRowsAndKeepsGameWithOpenConnection(t *testing.T) {
	// Given: an idle started game with one stale row and one open connection
	useManagementAPI(t, map[string]int{"c-stale": http.StatusGone})
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-mixed", 40*time.Minute))
	db.seed(testConnections, connectionRow("c-stale", "g-mixed", time.Hour))
	db.seed(testConnections, connectionRow("c-open", "g-mixed", time.Hour))
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then: the stale row goes, the open one stays, and the game is kept
	if db.item(testConnections, "c-stale") != nil {
		t.Fatal("stale connection row was not removed")
	}
	if db.item(testConnections, "c-open") == nil {
		t.Fatal("open connection row was removed")
	}
	if db.item(testGames, "g-mixed") == nil || result.Kept != 1 || result.Deleted != 0 {
		t.Fatalf("result = %+v, want the game kept", result)
	}
}

func TestJanitorKeepsGameWhenConnectionCheckFails(t *testing.T) {
	tests := []struct {
		name  string
		setup func(t *testing.T)
	}{
		{name: "API Gateway answers 500", setup: func(t *testing.T) {
			useManagementAPI(t, map[string]int{"c1": http.StatusInternalServerError})
		}},
		{name: "API Gateway answers 403", setup: func(t *testing.T) {
			useManagementAPI(t, map[string]int{"c1": http.StatusForbidden})
		}},
		{name: "management endpoint cannot be reached", setup: useUnreachableEndpoint},
		{name: "management endpoint is not configured", setup: func(t *testing.T) {
			setAWSTestEnv(t, "")
		}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Given: an idle game whose connection cannot be verified
			tt.setup(t)
			db := newFakeDynamo()
			db.seed(testGames, idleGame("g-unsure", 40*time.Minute))
			db.seed(testConnections, connectionRow("c1", "g-unsure", time.Hour))
			app := newTestApp(db)

			// When
			result := runJanitorForTest(t, app)

			// Then: nothing is deleted, the row is kept, and the game is counted as kept
			if db.item(testGames, "g-unsure") == nil || db.item(testConnections, "c1") == nil {
				t.Fatal("game or connection row was deleted although the connection is unverified")
			}
			if result.Kept != 1 || result.Deleted != 0 || result.Skipped != 0 {
				t.Fatalf("result = %+v, want one kept game", result)
			}
		})
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

func TestJanitorKeepsFinishedGames(t *testing.T) {
	// Given: a finished game, long idle with no connection
	db := newFakeDynamo()
	finished := idleGame("g-done", 3*time.Hour)
	finished["finished"] = boolValue(true)
	db.seed(testGames, finished)
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then
	if db.item(testGames, "g-done") == nil {
		t.Fatal("a finished game was deleted")
	}
	if result.Deleted != 0 {
		t.Fatalf("Deleted = %d, want 0", result.Deleted)
	}
}

func TestJanitorLobbyCases(t *testing.T) {
	tests := []struct {
		name           string
		lobby          map[string]types.AttributeValue
		connection     map[string]types.AttributeValue
		apiStatus      int // GetConnection status for c1; 0 means 200
		queryErr       error
		wantDeleted    bool
		wantRowDeleted bool
		wantSkipped    int
	}{
		{
			name:        "idle lobby with no connection is deleted",
			lobby:       lobbyGame("g", 20*time.Minute),
			wantDeleted: true,
		},
		{
			name:       "idle lobby with a live connection is kept",
			lobby:      lobbyGame("g", 40*time.Minute),
			connection: connectionRow("c1", "g", time.Hour),
		},
		{
			name:           "idle lobby whose connection is gone is deleted",
			lobby:          lobbyGame("g", 40*time.Minute),
			connection:     connectionRow("c1", "g", time.Hour),
			apiStatus:      http.StatusGone,
			wantDeleted:    true,
			wantRowDeleted: true,
		},
		{
			name:       "idle lobby whose connection cannot be verified is kept",
			lobby:      lobbyGame("g", 40*time.Minute),
			connection: connectionRow("c1", "g", time.Hour),
			apiStatus:  http.StatusInternalServerError,
		},
		{
			name:        "idle lobby whose only connection row has expired is deleted",
			lobby:       lobbyGame("g", 40*time.Minute),
			connection:  connectionRow("c1", "g", -time.Second),
			wantDeleted: true,
		},
		{
			name:  "lobby idle less than the grace period is kept",
			lobby: lobbyGame("g", 5*time.Minute),
		},
		{
			name:        "idle lobby is kept when its connections cannot be queried",
			lobby:       lobbyGame("g", 40*time.Minute),
			queryErr:    errors.New("throttled"),
			wantSkipped: 1,
		},
		{
			name:        "lobby without a boolean started flag is never deleted",
			lobby:       withoutAttribute(lobbyGame("g", 40*time.Minute), "started"),
			wantSkipped: 1,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Given
			statuses := map[string]int{}
			if tt.apiStatus != 0 {
				statuses["c1"] = tt.apiStatus
			}
			useManagementAPI(t, statuses)
			db := newFakeDynamo()
			db.seed(testGames, tt.lobby)
			if tt.connection != nil {
				db.seed(testConnections, tt.connection)
			}
			db.queryErr = tt.queryErr
			app := newTestApp(db)

			// When
			result := runJanitorForTest(t, app)

			// Then
			deleted := db.item(testGames, "g") == nil
			if deleted != tt.wantDeleted {
				t.Fatalf("deleted = %v, want %v (result %+v)", deleted, tt.wantDeleted, result)
			}
			if tt.connection != nil && (db.item(testConnections, "c1") == nil) != tt.wantRowDeleted {
				t.Fatalf("connection row deleted = %v, want %v", db.item(testConnections, "c1") == nil, tt.wantRowDeleted)
			}
			if result.Skipped != tt.wantSkipped {
				t.Fatalf("Skipped = %d, want %d", result.Skipped, tt.wantSkipped)
			}
		})
	}
}

func TestJanitorLobbySavedDuringRunIsKept(t *testing.T) {
	// Given: an idle lobby, but a player joins (saves it) between the scan and the delete
	db := newFakeDynamo()
	db.seed(testGames, lobbyGame("g-lobby-race", 20*time.Minute))
	db.onQuery = func() {
		db.onQuery = nil
		db.seed(testGames, lobbyGame("g-lobby-race", time.Minute))
	}
	app := newTestApp(db)

	// When
	result := runJanitorForTest(t, app)

	// Then: the freshly saved lobby survives and the lost race is counted as kept
	if db.item(testGames, "g-lobby-race") == nil {
		t.Fatal("lobby saved during the run was deleted")
	}
	if result.Kept != 1 || result.Skipped != 0 || result.Deleted != 0 {
		t.Fatalf("result = %+v, want one kept lobby and no errors", result)
	}
}

func TestJanitorRequestsReferenceEveryExpressionName(t *testing.T) {
	// Given: an idle started game and an idle lobby, both abandoned
	fake := newFakeDynamo()
	fake.seed(testGames, idleGame("g-started", 20*time.Minute))
	fake.seed(testGames, lobbyGame("g-lobby", 20*time.Minute))
	app := newTestApp(strictDynamo{fakeDynamo: fake, t: t})

	// When
	result := runJanitorForTest(t, app)

	// Then: both are deleted, and every request passed the unused-name check
	if result.Deleted != 2 {
		t.Fatalf("Deleted = %d, want 2 (result %+v)", result.Deleted, result)
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
	api := useManagementAPI(t, nil)
	db := newFakeDynamo()
	db.seed(testGames, idleGame("g-expired-conn", 20*time.Minute))
	db.seed(testConnections, connectionRow("stale", "g-expired-conn", -time.Second))
	app := newTestApp(db)

	// When
	runJanitorForTest(t, app)

	// Then: the row is not live, so API Gateway is not asked about it
	if db.item(testGames, "g-expired-conn") != nil {
		t.Fatal("game was kept because of a connection row whose ttl had passed")
	}
	if api.callCount() != 0 {
		t.Fatalf("GetConnection calls = %d, want 0 for an expired row", api.callCount())
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
