package main

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

var testNow = time.Date(2026, 10, 9, 20, 0, 0, 0, time.UTC)

func testSettings() Settings {
	return Settings{GameSessionsTable: testGames, UsersTable: testUsers, ConnectionsTable: testConnections}
}

// newTestApp wires an App to the fake with a fixed clock and a scripted session-code sequence.
func newTestApp(db Dynamo, codes ...string) *App {
	app := NewApp(db, testSettings(), nil)
	app.now = func() time.Time { return testNow }
	app.newCode = func() (string, error) {
		if len(codes) == 0 {
			return "ZZZZZZ", nil
		}
		code := codes[0]
		codes = codes[1:]
		return code, nil
	}
	return app
}

func mustRaw(t *testing.T, v any) json.RawMessage {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func restCreateGame(sub, body string) map[string]any {
	return map[string]any{
		"httpMethod": "POST",
		"path":       "/create-game",
		"body":       body,
		"requestContext": map[string]any{
			"authorizer": map[string]any{"claims": map[string]any{"sub": sub}},
		},
	}
}

func wsRoute(routeKey, eventType, connectionID string, authorizer map[string]any, query map[string]string) map[string]any {
	return map[string]any{
		"requestContext": map[string]any{
			"routeKey":     routeKey,
			"eventType":    eventType,
			"connectionId": connectionID,
			"domainName":   "abc.execute-api.eu-central-1.amazonaws.com",
			"stage":        "prod",
			"authorizer":   authorizer,
		},
		"queryStringParameters": query,
		"body":                  `{"action":"` + routeKey + `"}`,
	}
}

func proxyResponse(t *testing.T, result any) events.APIGatewayProxyResponse {
	t.Helper()
	resp, ok := result.(events.APIGatewayProxyResponse)
	if !ok {
		t.Fatalf("unexpected result type %T", result)
	}
	return resp
}

func TestHandleRejectsUnknownShape(t *testing.T) {
	app := newTestApp(newFakeDynamo())
	if _, err := app.Handle(context.Background(), json.RawMessage(`{"something":"else"}`)); err == nil {
		t.Fatal("expected an error for an unrecognised event")
	}
}

func TestDefaultRouteIsANoOpAndWritesNothing(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db)
	raw := mustRaw(t, wsRoute("$default", "MESSAGE", "conn-1", nil, nil))

	resp := proxyResponse(t, mustHandle(t, app, raw))
	if resp.StatusCode != 200 {
		t.Fatalf("$default should return 200, got %d", resp.StatusCode)
	}
	if n := db.writes(""); n != 0 {
		t.Fatalf("$default must not touch DynamoDB, got %d writes", n)
	}
}

func TestUnknownWebSocketRouteIsNotFound(t *testing.T) {
	app := newTestApp(newFakeDynamo())
	raw := mustRaw(t, wsRoute("nonsense", "MESSAGE", "conn-1", nil, nil))
	if resp := proxyResponse(t, mustHandle(t, app, raw)); resp.StatusCode != 404 {
		t.Fatalf("unknown route should return 404, got %d", resp.StatusCode)
	}
}

func TestConnectStoresConnectionWithSessionAndUser(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db)
	raw := mustRaw(t, wsRoute("$connect", "CONNECT", "conn-1",
		map[string]any{"sub": "user-1", "username": "alice"}, map[string]string{"game_session_id": "GAME01"}))

	resp := proxyResponse(t, mustHandle(t, app, raw))
	if resp.StatusCode != 200 {
		t.Fatalf("$connect status %d", resp.StatusCode)
	}
	item := db.item(testConnections, "conn-1")
	if stringAttr(item, "game_session_id") != "GAME01" || stringAttr(item, "user_id") != "user-1" {
		t.Fatalf("connection item missing attributes: %v", item)
	}
	if ttl, ok := item["ttl"].(*types.AttributeValueMemberN); !ok || ttl.Value != strconv.FormatInt(testNow.Unix()+3600, 10) {
		t.Fatalf("ttl should be now + 3600, got %v", item["ttl"])
	}
}

func TestDisconnectDeletesConnection(t *testing.T) {
	db := newFakeDynamo()
	db.seed(testConnections, map[string]types.AttributeValue{"connection_id": s("conn-1")})
	app := newTestApp(db)

	resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, wsRoute("$disconnect", "DISCONNECT", "conn-1", nil, nil))))
	if resp.StatusCode != 200 {
		t.Fatalf("$disconnect status %d", resp.StatusCode)
	}
	if db.item(testConnections, "conn-1") != nil {
		t.Fatal("connection should have been deleted")
	}
}

func TestCreateGameWritesSessionForOwner(t *testing.T) {
	db := newFakeDynamo()
	db.seed(testUsers, map[string]types.AttributeValue{"user_id": s("owner"), "username": s("Tomi")})
	app := newTestApp(db, "ABC123")

	body := `{"config":{"decksCount":2,"cardRules":{"5":"JOKER"},"allowFailedFaceUpPlay":true,"ignoredKey":1}}`
	resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, restCreateGame("owner", body))))
	if resp.StatusCode != 200 {
		t.Fatalf("create-game status %d: %s", resp.StatusCode, resp.Body)
	}
	if !strings.Contains(resp.Body, `"sessionId":"ABC123"`) {
		t.Fatalf("response body %s", resp.Body)
	}
	game := db.item(testGames, "ABC123")
	if game == nil {
		t.Fatal("game item not written")
	}
	if stringAttr(game, "user_id") != "owner" || stringAttr(game, "created_at") != "2026-10-09T20:00:00.000000+00:00" {
		t.Fatalf("unexpected game attributes: %v", game)
	}
	if _, ok := game["shitheadId"].(*types.AttributeValueMemberNULL); !ok {
		t.Fatalf("shitheadId should be NULL, got %T", game["shitheadId"])
	}
	config := game["config"].(*types.AttributeValueMemberM).Value
	if config["burnCount"].(*types.AttributeValueMemberN).Value != "6" {
		t.Fatalf("two decks should give burnCount 6")
	}
	if _, ok := config["allowFailedFaceUpPlay"].(*types.AttributeValueMemberBOOL); !ok {
		t.Fatal("allowFailedFaceUpPlay should be stored as BOOL")
	}
	players := game["players"].(*types.AttributeValueMemberL).Value
	if len(players) != 1 || playerIDOf(players[0]) != "owner" {
		t.Fatalf("owner should be the only player: %v", players)
	}
}

func TestCreateGameDefaultsUsernameToUnknown(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db, "NEW001")
	proxyResponse(t, mustHandle(t, app, mustRaw(t, restCreateGame("nobody", ""))))
	players := db.item(testGames, "NEW001")["players"].(*types.AttributeValueMemberL).Value
	if name := stringAttr(players[0].(*types.AttributeValueMemberM).Value, "username"); name != "Unknown" {
		t.Fatalf("username = %q, want Unknown", name)
	}
}

func TestCreateGameRejectsInvalidConfigWithoutWriting(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db, "BAD001")
	resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, restCreateGame("owner", `{"config":{"decksCount":3}}`))))
	if resp.StatusCode != 400 {
		t.Fatalf("invalid config should return 400, got %d", resp.StatusCode)
	}
	if n := db.writes("PutItem"); n != 0 {
		t.Fatalf("invalid config must not write, got %d puts", n)
	}
}

func TestCreateGameHandsOverUnstartedLobbyAndDeletesEmptyOnes(t *testing.T) {
	db := newFakeDynamo()
	db.seed(testGames, lobby("EMPTY1", "owner", false, "owner"))
	db.seed(testGames, lobby("HANDED", "owner", false, "owner", "guest"))
	db.seed(testGames, lobby("STARTD", "owner", true, "owner", "guest"))
	app := newTestApp(db, "NEWGAM")

	proxyResponse(t, mustHandle(t, app, mustRaw(t, restCreateGame("owner", ""))))

	if db.item(testGames, "EMPTY1") != nil {
		t.Fatal("lobby with no other players should be deleted")
	}
	handed := db.item(testGames, "HANDED")
	if stringAttr(handed, "user_id") != "guest" || len(handed["players"].(*types.AttributeValueMemberL).Value) != 1 {
		t.Fatalf("lobby should be handed to guest: %v", handed)
	}
	if stringAttr(db.item(testGames, "STARTD"), "user_id") != "owner" {
		t.Fatal("started games must be left alone")
	}
}

func TestCognitoTriggerSeedsProfileWithoutOverwritingName(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db)
	event := mustRaw(t, map[string]any{
		"triggerSource": "PostConfirmation_ConfirmSignUp",
		"request": map[string]any{"userAttributes": map[string]string{
			"sub": "user-9", "email": "player@example.com",
		}},
	})
	result, err := app.Handle(context.Background(), event)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := result.(json.RawMessage); !ok {
		t.Fatalf("trigger must return the event, got %T", result)
	}
	profile := db.item(testUsers, "user-9")
	if stringAttr(profile, "username") != "player@example.com" || stringAttr(profile, "leaderboard_pk") != "global" {
		t.Fatalf("profile not seeded: %v", profile)
	}
	if n := profile["elo_score"].(*types.AttributeValueMemberN).Value; n != "1000" {
		t.Fatalf("elo_score = %s", n)
	}

	db.seed(testUsers, map[string]types.AttributeValue{"user_id": s("user-9"), "username": s("Renamed")})
	if _, err := app.Handle(context.Background(), event); err != nil {
		t.Fatal(err)
	}
	if stringAttr(db.item(testUsers, "user-9"), "username") != "Renamed" {
		t.Fatal("a user-edited name must survive later logins")
	}
}

// lobby builds an unstarted or started game item owned by owner with the given players.
func lobby(id, owner string, started bool, playerIDs ...string) map[string]types.AttributeValue {
	players := make([]types.AttributeValue, 0, len(playerIDs))
	for _, playerID := range playerIDs {
		players = append(players, &types.AttributeValueMemberM{Value: map[string]types.AttributeValue{
			"playerId": s(playerID),
		}})
	}
	return map[string]types.AttributeValue{
		"game_id": s(id),
		"user_id": s(owner),
		"started": &types.AttributeValueMemberBOOL{Value: started},
		"players": &types.AttributeValueMemberL{Value: players},
	}
}

func mustHandle(t *testing.T, app *App, raw json.RawMessage) any {
	t.Helper()
	result, err := app.Handle(context.Background(), raw)
	if err != nil {
		t.Fatalf("handler error: %v", err)
	}
	return result
}
