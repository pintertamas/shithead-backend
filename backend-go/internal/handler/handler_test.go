package handler

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/pintertamas/shithead-backend/backend-go/internal/auth"
	"github.com/pintertamas/shithead-backend/backend-go/internal/game"
	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
	"github.com/pintertamas/shithead-backend/backend-go/internal/store"
)

// ---- event builders -------------------------------------------------------

func rawEvent(t *testing.T, v any) json.RawMessage {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func (fx *fixture) rest(t *testing.T, method, path, body string, claims map[string]any) events.APIGatewayProxyResponse {
	t.Helper()
	ev := map[string]any{
		"httpMethod": method,
		"path":       path,
		"body":       body,
		"requestContext": map[string]any{
			"stage":      "prod",
			"authorizer": map[string]any{"claims": claims},
		},
	}
	if claims == nil {
		ev["requestContext"] = map[string]any{"stage": "prod", "authorizer": map[string]any{}}
	}
	out, err := fx.h.Handle(context.Background(), rawEvent(t, ev))
	if err != nil {
		t.Fatalf("handle %s %s: %v", method, path, err)
	}
	return out.(events.APIGatewayProxyResponse)
}

func (fx *fixture) ws(t *testing.T, route, connID, body string, qs map[string]string, sub string) events.APIGatewayProxyResponse {
	t.Helper()
	authorizer := map[string]any{}
	if sub != "" {
		authorizer["sub"] = sub
	}
	ev := map[string]any{
		"body":                  body,
		"queryStringParameters": qs,
		"requestContext": map[string]any{
			"routeKey":     route,
			"connectionId": connID,
			"domainName":   "api.example.com",
			"stage":        "$default",
			"authorizer":   authorizer,
		},
	}
	out, err := fx.h.Handle(context.Background(), rawEvent(t, ev))
	if err != nil {
		t.Fatalf("handle ws %s: %v", route, err)
	}
	return out.(events.APIGatewayProxyResponse)
}

func (fx *fixture) connect(t *testing.T, connID, sessionID, sub string) {
	t.Helper()
	resp := fx.ws(t, "$connect", connID, "", map[string]string{"game_session_id": sessionID}, sub)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("connect %s failed with %d", connID, resp.StatusCode)
	}
}

func decodeMap(t *testing.T, body string) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatalf("invalid json %q: %v", body, err)
	}
	return out
}

func (fx *fixture) seedGame(t *testing.T, rec store.GameRecord) {
	t.Helper()
	if err := fx.games.Put(context.Background(), rec); err != nil {
		t.Fatal(err)
	}
}

// lobby creates a game for owner alice with bob already joined.
func (fx *fixture) lobby(t *testing.T) string {
	t.Helper()
	fx.users.seed(store.UserRecord{UserID: "alice", Username: "Alice", EloScore: 1000})
	fx.users.seed(store.UserRecord{UserID: "bob", Username: "Bob", EloScore: 1000})
	resp := fx.rest(t, http.MethodPost, "/create-game", "{}", claimsFor("alice", "Alice"))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("create-game failed %d %s", resp.StatusCode, resp.Body)
	}
	id := decodeMap(t, resp.Body)["sessionId"].(string)
	if r := fx.rest(t, http.MethodPost, "/join-game", `{"sessionId":"`+id+`"}`, claimsFor("bob", "Bob")); r.StatusCode != http.StatusOK {
		t.Fatalf("join failed %d", r.StatusCode)
	}
	return id
}

// ---- routing --------------------------------------------------------------

func TestRouting_optionsAnswersWithCORS(t *testing.T) {
	fx := newFixture()

	resp := fx.rest(t, http.MethodOptions, "/state/ABC", "", nil)

	if resp.StatusCode != http.StatusOK || resp.Headers["Access-Control-Allow-Origin"] != "*" {
		t.Fatalf("OPTIONS must return 200 with CORS headers, got %d %v", resp.StatusCode, resp.Headers)
	}
}

func TestRouting_unknownRoute_returns404(t *testing.T) {
	fx := newFixture()

	if resp := fx.rest(t, http.MethodGet, "/nope", "", claimsFor("alice", "A")); resp.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown route must be 404, got %d", resp.StatusCode)
	}
}

func TestRouting_stagePrefixIsStripped(t *testing.T) {
	fx := newFixture()

	resp := fx.rest(t, http.MethodGet, "/prod/leaderboard/top", "", nil)

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("stage-prefixed path must route, got %d", resp.StatusCode)
	}
}

func TestRouting_unsupportedEvent_returnsError(t *testing.T) {
	fx := newFixture()

	if _, err := fx.h.Handle(context.Background(), json.RawMessage(`{"foo":1}`)); !errors.Is(err, ErrUnsupportedEvent) {
		t.Fatalf("unknown event shape must fail, got %v", err)
	}
}

func TestRouting_authenticatedRouteWithoutClaims_returns401(t *testing.T) {
	fx := newFixture()

	if resp := fx.rest(t, http.MethodPost, "/create-game", "{}", nil); resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("missing claims must be 401, got %d", resp.StatusCode)
	}
}

// ---- lobby ----------------------------------------------------------------

func TestCreateGame_storesLobbyWithOwnerAsOnlySeat(t *testing.T) {
	fx := newFixture()
	fx.users.seed(store.UserRecord{UserID: "alice", Username: "Alice"})

	resp := fx.rest(t, http.MethodPost, "/create-game", `{"config":{"decksCount":2}}`, claimsFor("alice", "Alice"))

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	id := decodeMap(t, resp.Body)["sessionId"].(string)
	rec, _ := fx.games.Get(context.Background(), id)
	if rec == nil || rec.OwnerID != "alice" || len(rec.Players) != 1 || rec.Players[0].Username != "Alice" {
		t.Fatalf("lobby not stored correctly: %+v", rec)
	}
	if rec.Config.DecksCount != 2 || rec.Config.BurnCount != 6 {
		t.Fatalf("two-deck config not stored: %+v", rec.Config)
	}
}

func TestCreateGame_invalidConfig_returns400(t *testing.T) {
	fx := newFixture()

	resp := fx.rest(t, http.MethodPost, "/create-game", `{"config":{"decksCount":3}}`, claimsFor("alice", "A"))

	if resp.StatusCode != http.StatusBadRequest || !strings.Contains(resp.Body, "Invalid game configuration") {
		t.Fatalf("invalid config must be rejected, got %d %s", resp.StatusCode, resp.Body)
	}
}

func TestCreateGame_leavesOwnersOtherLobbiesFirst(t *testing.T) {
	fx := newFixture()
	first := decodeMap(t, fx.rest(t, http.MethodPost, "/create-game", "{}", claimsFor("alice", "A")).Body)["sessionId"].(string)

	fx.rest(t, http.MethodPost, "/create-game", "{}", claimsFor("alice", "A"))

	if rec, _ := fx.games.Get(context.Background(), first); rec != nil {
		t.Fatal("an unstarted lobby owned by the same user must be cleaned up")
	}
}

func TestJoinGame_statusCodes(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)

	if r := fx.rest(t, http.MethodPost, "/join-game", `{"sessionId":"NOPE"}`, claimsFor("carol", "C")); r.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown game must be 404, got %d", r.StatusCode)
	}
	if r := fx.rest(t, http.MethodPost, "/join-game", `{"sessionId":"`+id+`"}`, claimsFor("bob", "B")); r.StatusCode != http.StatusOK {
		t.Fatalf("rejoining must be idempotent, got %d", r.StatusCode)
	}
	if r := fx.rest(t, http.MethodPost, "/join-game", `not json`, claimsFor("carol", "C")); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("malformed body must be 400, got %d", r.StatusCode)
	}
}

func TestLeaveGame_lastPlayerDeletesGame(t *testing.T) {
	fx := newFixture()
	fx.users.seed(store.UserRecord{UserID: "solo", Username: "Solo"})
	id := decodeMap(t, fx.rest(t, http.MethodPost, "/create-game", "{}", claimsFor("solo", "Solo")).Body)["sessionId"].(string)

	if r := fx.rest(t, http.MethodPost, "/leave-game", `{"sessionId":"`+id+`"}`, claimsFor("solo", "Solo")); r.StatusCode != http.StatusOK {
		t.Fatalf("leave must succeed, got %d", r.StatusCode)
	}
	if rec, _ := fx.games.Get(context.Background(), id); rec != nil {
		t.Fatal("an empty lobby must be deleted")
	}
}

func TestStartGame_ownerOnlyAndNeedsTwoPlayers(t *testing.T) {
	fx := newFixture()
	fx.users.seed(store.UserRecord{UserID: "alice", Username: "Alice"})
	id := decodeMap(t, fx.rest(t, http.MethodPost, "/create-game", "{}", claimsFor("alice", "Alice")).Body)["sessionId"].(string)

	if r := fx.rest(t, http.MethodPost, "/start-game", `{"sessionId":"`+id+`"}`, claimsFor("alice", "Alice")); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("a single-player start must be 400, got %d", r.StatusCode)
	}
	fx.rest(t, http.MethodPost, "/join-game", `{"sessionId":"`+id+`"}`, claimsFor("bob", "Bob"))
	if r := fx.rest(t, http.MethodPost, "/start-game", `{"sessionId":"`+id+`"}`, claimsFor("bob", "Bob")); r.StatusCode != http.StatusForbidden {
		t.Fatalf("non-owner start must be 403, got %d", r.StatusCode)
	}
}

func TestStartGame_prepareThenDeal(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)

	prep := fx.rest(t, http.MethodPost, "/start-game", `{"sessionId":"`+id+`","phase":"prepare"}`, claimsFor("alice", "Alice"))
	if prep.StatusCode != http.StatusOK || decodeMap(t, prep.Body)["starting"] != true {
		t.Fatalf("prepare must set starting, got %d %s", prep.StatusCode, prep.Body)
	}
	if rec, _ := fx.games.Get(context.Background(), id); rec == nil || !rec.Starting || rec.Started {
		t.Fatal("prepare must persist starting without starting the game")
	}

	if r := fx.rest(t, http.MethodPost, "/start-game", `{"sessionId":"`+id+`"}`, claimsFor("alice", "Alice")); r.StatusCode != http.StatusOK {
		t.Fatalf("start must succeed, got %d", r.StatusCode)
	}
	rec, _ := fx.games.Get(context.Background(), id)
	if !rec.Started || rec.Starting || rec.SetupComplete {
		t.Fatalf("deal must start the game, open setup and clear starting: %+v", rec)
	}
	for _, p := range rec.Players {
		if len(p.Hand) != 3 || len(p.FaceUp) != 3 || len(p.FaceDown) != 3 {
			t.Fatal("every seat must be dealt 3/3/3")
		}
	}
}

func TestLeaveGame_startedGame_returns409WithMessage(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.rest(t, http.MethodPost, "/start-game", `{"sessionId":"`+id+`"}`, claimsFor("alice", "Alice"))

	resp := fx.rest(t, http.MethodPost, "/leave-game", `{"sessionId":"`+id+`"}`, claimsFor("bob", "Bob"))

	if resp.StatusCode != http.StatusConflict || decodeMap(t, resp.Body)["error"] != "Cannot leave a started game" {
		t.Fatalf("expected 409 with message, got %d %s", resp.StatusCode, resp.Body)
	}
}

func TestGetState_hidesOtherPlayersHands(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.rest(t, http.MethodPost, "/start-game", `{"sessionId":"`+id+`"}`, claimsFor("alice", "Alice"))

	resp := fx.rest(t, http.MethodGet, "/state/"+id, "", claimsFor("alice", "Alice"))

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("state must load, got %d", resp.StatusCode)
	}
	var view stateView
	if err := json.Unmarshal([]byte(resp.Body), &view); err != nil {
		t.Fatal(err)
	}
	if !view.IsOwner || len(view.Players) != 2 {
		t.Fatalf("unexpected view: %+v", view)
	}
	for _, p := range view.Players {
		if p.IsYou && len(p.Hand) != 3 {
			t.Fatal("viewer must see their own hand")
		}
		if !p.IsYou && (len(p.Hand) != 0 || p.HandCount != 3 || p.FaceDownCount != 3) {
			t.Fatal("opponents' hands must be hidden")
		}
	}
	if view.AllowFailedFaceUpPlay {
		t.Fatal("allowFailedFaceUpPlay must be false by default")
	}
}

func TestGetState_missingGame_returns404(t *testing.T) {
	fx := newFixture()

	if resp := fx.rest(t, http.MethodGet, "/state/NOPE", "", claimsFor("alice", "A")); resp.StatusCode != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", resp.StatusCode)
	}
}

// ---- leaderboards ---------------------------------------------------------

func TestLeaderboardTop_clampsLimitAndDefaults(t *testing.T) {
	fx := newFixture()
	fx.users.seed(store.UserRecord{UserID: "a", Username: "A", EloScore: 1100})

	query := func(limit string) {
		req := events.APIGatewayProxyRequest{
			HTTPMethod:            http.MethodGet,
			Path:                  "/leaderboard/top",
			QueryStringParameters: map[string]string{"limit": limit},
		}
		fx.h.rest(context.Background(), req)
	}

	query("500")
	if fx.users.lastLimit != 100 {
		t.Fatalf("limit must clamp to 100, got %d", fx.users.lastLimit)
	}
	query("abc")
	if fx.users.lastLimit != 20 {
		t.Fatalf("invalid limit must fall back to 20, got %d", fx.users.lastLimit)
	}
}

func TestLeaderboardSession_unknownPlayersShowAsUnknownZeroElo(t *testing.T) {
	fx := newFixture()
	fx.users.seed(store.UserRecord{UserID: "alice", Username: "Alice", EloScore: 1200})
	fx.seedGame(t, store.NewGameRecord("LB1", "alice", "Alice", game.DefaultConfig(), fx.now))
	rec, _ := fx.games.Get(context.Background(), "LB1")
	rec.Players = append(rec.Players, store.PlayerRecord{PlayerID: "ghost", Username: "Ghost", Ready: boolPtrOf(true)})
	fx.seedGame(t, *rec)

	resp := fx.rest(t, http.MethodGet, "/leaderboard/session/LB1", "", claimsFor("alice", "A"))

	var entries []leaderboardEntry
	if err := json.Unmarshal([]byte(resp.Body), &entries); err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 || entries[0].EloScore != 1200 || entries[1].Username != "Unknown" || entries[1].EloScore != 0 {
		t.Fatalf("unexpected entries: %+v", entries)
	}
}

func boolPtrOf(v bool) *bool { return &v }

// ---- profile --------------------------------------------------------------

func TestProfile_lazyCreatesWithDefaultNicknameFromClaims(t *testing.T) {
	fx := newFixture()

	resp := fx.rest(t, http.MethodGet, "/profile", "", claimsFor("abcdef123456", "alice"))

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("profile must load, got %d", resp.StatusCode)
	}
	body := decodeMap(t, resp.Body)
	if body["username"] != "alice" || body["canClearGames"] != false {
		t.Fatalf("unexpected profile: %v", body)
	}
	if u, _ := fx.users.Get(context.Background(), "abcdef123456"); u == nil || u.EloScore != 1000 {
		t.Fatal("profile must be created with elo 1000")
	}
}

func TestProfile_duplicateDefaultNickname_getsUniqueSuffix(t *testing.T) {
	fx := newFixture()
	fx.users.seed(store.UserRecord{UserID: "other", Username: "alice"})

	body := decodeMap(t, fx.rest(t, http.MethodGet, "/profile", "", claimsFor("abcdef123456", "alice")).Body)

	if body["username"] != "alice-123456" {
		t.Fatalf("expected alice-<last six of sub>, got %v", body["username"])
	}
}

func TestUpdateProfile_validationAndTaken(t *testing.T) {
	fx := newFixture()
	fx.users.seed(store.UserRecord{UserID: "other", Username: "Taken"})

	if r := fx.rest(t, http.MethodPut, "/profile", `{"username":"x"}`, claimsFor("me", "me")); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("too-short name must be 400, got %d", r.StatusCode)
	}
	if r := fx.rest(t, http.MethodPut, "/profile", `{"username":"bad<name>"}`, claimsFor("me", "me")); r.StatusCode != http.StatusBadRequest {
		t.Fatalf("disallowed characters must be 400, got %d", r.StatusCode)
	}
	if r := fx.rest(t, http.MethodPut, "/profile", `{"username":"taken"}`, claimsFor("me", "me")); r.StatusCode != http.StatusConflict {
		t.Fatalf("taken nickname must be 409, got %d", r.StatusCode)
	}
}

func TestUpdateProfile_renamesPlayerInActiveGames(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)

	resp := fx.rest(t, http.MethodPut, "/profile", `{"username":"Bobby Tables"}`, claimsFor("bob", "Bob"))

	if resp.StatusCode != http.StatusOK || decodeMap(t, resp.Body)["username"] != "Bobby Tables" {
		t.Fatalf("rename must succeed, got %d %s", resp.StatusCode, resp.Body)
	}
	rec, _ := fx.games.Get(context.Background(), id)
	if rec.Players[1].Username != "Bobby Tables" {
		t.Fatal("the seat name in active games must follow the rename")
	}
}

// ---- admin ----------------------------------------------------------------

func TestDoomsday_requiresGameAdmin(t *testing.T) {
	fx := newFixture()

	resp := fx.rest(t, http.MethodPost, "/admin/doomsday", "", claimsFor("alice", "A"))

	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("non-admin must get 403, got %d", resp.StatusCode)
	}
}

func TestDoomsday_closesConnectionsDeletesGamesKeepsProfiles(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.connect(t, "c1", id, "alice")
	fx.connect(t, "gone-1", id, "bob")
	fx.notifier.gone["gone-1"] = true
	fx.connect(t, "fail-1", id, "bob")
	fx.connect(t, "c-admin", "", "admin")

	resp := fx.rest(t, http.MethodPost, "/admin/doomsday", "", adminClaims("admin"))

	body := decodeMap(t, resp.Body)
	if resp.StatusCode != http.StatusOK || body["deletedGames"] != float64(1) ||
		body["closedConnections"] != float64(2) || body["failedConnections"] != float64(1) {
		t.Fatalf("unexpected doomsday result %d %s", resp.StatusCode, resp.Body)
	}
	if rec, _ := fx.games.Get(context.Background(), id); rec != nil {
		t.Fatal("games must be deleted")
	}
	if u, _ := fx.users.Get(context.Background(), "alice"); u == nil {
		t.Fatal("profiles must be kept")
	}
	if fx.conns.has("gone-1") || fx.conns.has("c1") {
		t.Fatal("records of closed and gone connections must be removed")
	}
	if !fx.conns.has("fail-1") {
		t.Fatal("a failed close must keep the connection record")
	}
}

// ---- websocket ------------------------------------------------------------

func TestWebSocket_connectAndDisconnect_maintainRegistry(t *testing.T) {
	fx := newFixture()

	fx.connect(t, "c1", "GAME1", "alice")
	if !fx.conns.has("c1") {
		t.Fatal("connect must register the connection")
	}
	if resp := fx.ws(t, "$disconnect", "c1", "", nil, ""); resp.StatusCode != http.StatusOK || fx.conns.has("c1") {
		t.Fatal("disconnect must remove the connection")
	}
}

func TestWebSocket_defaultRouteIsNoOp(t *testing.T) {
	fx := newFixture()

	resp := fx.ws(t, "$default", "c1", `{"action":"anything","data":"x"}`, nil, "")

	if resp.StatusCode != http.StatusOK || len(fx.notifier.sent) != 0 {
		t.Fatal("$default must answer 200 and send nothing")
	}
}

func TestWebSocket_playFromOutsideGame_sendsForbiddenError(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.connect(t, "stranger", id, "carol")

	resp := fx.ws(t, "play", "stranger", `{"action":"play","sessionId":"`+id+`","selections":[]}`, nil, "")

	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403, got %d", resp.StatusCode)
	}
	msgs := fx.notifier.messagesTo("stranger")
	if len(msgs) != 1 || !strings.Contains(string(msgs[0].Data), "Join this game before sending actions.") {
		t.Fatalf("sender must get the error message, got %v", msgs)
	}
}

func TestWebSocket_playOutOfTurn_sendsError(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.rest(t, http.MethodPost, "/start-game", `{"sessionId":"`+id+`"}`, claimsFor("alice", "Alice"))
	fx.connect(t, "bob-c", id, "bob")

	resp := fx.ws(t, "play", "bob-c", `{"action":"play","sessionId":"`+id+`","selections":[{"source":"HAND","index":0}]}`, nil, "")

	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", resp.StatusCode)
	}
	if !strings.Contains(string(fx.notifier.messagesTo("bob-c")[0].Data), "It is not your turn.") {
		t.Fatal("out-of-turn play must send the turn message")
	}
}

func TestWebSocket_setupFlow_announceSwapReadyBroadcastsPerViewer(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.connect(t, "a-c", id, "alice")
	fx.connect(t, "b-c", id, "bob")

	// Owner announces the start
	if r := fx.ws(t, "setup", "a-c", `{"action":"setup","sessionId":"`+id+`","setupAction":"announce"}`, nil, ""); r.StatusCode != http.StatusOK {
		t.Fatalf("announce must succeed, got %d", r.StatusCode)
	}
	if rec, _ := fx.games.Get(context.Background(), id); !rec.Starting {
		t.Fatal("announce must persist starting")
	}
	if r := fx.ws(t, "setup", "b-c", `{"action":"setup","sessionId":"`+id+`","setupAction":"announce"}`, nil, ""); r.StatusCode != http.StatusForbidden {
		t.Fatalf("non-owner announce must be 403, got %d", r.StatusCode)
	}

	fx.rest(t, http.MethodPost, "/start-game", `{"sessionId":"`+id+`"}`, claimsFor("alice", "Alice"))
	fx.notifier.reset()

	// Swap then ready
	if r := fx.ws(t, "setup", "a-c", `{"action":"setup","sessionId":"`+id+`","setupAction":"swap","handIndex":0,"faceUpIndex":0}`, nil, ""); r.StatusCode != http.StatusOK {
		t.Fatalf("swap must succeed, got %d", r.StatusCode)
	}
	if r := fx.ws(t, "setup", "a-c", `{"action":"setup","sessionId":"`+id+`","setupAction":"ready"}`, nil, ""); r.StatusCode != http.StatusOK {
		t.Fatalf("ready must succeed, got %d", r.StatusCode)
	}

	// Each viewer receives a view with only their own hand
	msgs := fx.notifier.messagesTo("b-c")
	if len(msgs) == 0 {
		t.Fatal("bob must receive a broadcast")
	}
	var view stateView
	if err := json.Unmarshal(msgs[len(msgs)-1].Data, &view); err != nil {
		t.Fatal(err)
	}
	for _, p := range view.Players {
		if p.IsYou && p.PlayerID != "bob" {
			t.Fatal("bob's view must mark bob as you")
		}
		if p.PlayerID == "alice" && len(p.Hand) != 0 {
			t.Fatal("bob must not see alice's hand")
		}
	}
	if msgs[len(msgs)-1].Endpoint != "https://api.example.com/$default" {
		t.Fatalf("broadcast must use the request endpoint, got %s", msgs[len(msgs)-1].Endpoint)
	}
}

func TestWebSocket_setupOutsideSetup_sendsError(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.connect(t, "a-c", id, "alice")

	resp := fx.ws(t, "setup", "a-c", `{"action":"setup","sessionId":"`+id+`","setupAction":"ready"}`, nil, "")

	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("readiness before the deal must be rejected, got %d", resp.StatusCode)
	}
}

func TestWebSocket_pickup_movesPileAndBroadcasts(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.seedGame(t, store.GameRecord{
		GameID: id, OwnerID: "alice", CurrentPlayerID: "alice", SetupComplete: true, Started: true,
		Players: []store.PlayerRecord{
			{PlayerID: "alice", Username: "Alice", Hand: []rules.Card{}, FaceUp: []rules.Card{}, FaceDown: []rules.Card{{Suit: rules.SuitClubs, Value: 4}}, Ready: boolPtrOf(true)},
			{PlayerID: "bob", Username: "Bob", Hand: []rules.Card{{Suit: rules.SuitHearts, Value: 6}}, FaceUp: []rules.Card{}, FaceDown: []rules.Card{}, Ready: boolPtrOf(true)},
		},
		DiscardPile: []rules.Card{{Suit: rules.SuitHearts, Value: 9}},
		Deck:        []rules.Card{},
		Config:      storeConfig(game.DefaultConfig()),
	})
	fx.connect(t, "a-c", id, "alice")

	resp := fx.ws(t, "pickup", "a-c", `{"action":"pickup","sessionId":"`+id+`"}`, nil, "")

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pickup must succeed, got %d", resp.StatusCode)
	}
	rec, _ := fx.games.Get(context.Background(), id)
	if len(rec.DiscardPile) != 0 || len(rec.Players[0].Hand) != 1 || rec.CurrentPlayerID != "bob" {
		t.Fatalf("pickup state wrong: %+v", rec)
	}
}

func storeConfig(c game.GameConfig) *store.ConfigRecord {
	rec := store.NewGameRecord("tmp", "x", "x", c, time.Now())
	return rec.Config
}

// failedFaceUpSetup seeds a game where alice must play a face-up card that cannot be played.
func (fx *fixture) failedFaceUpSetup(t *testing.T, id string, allowFailed bool) {
	t.Helper()
	cfg := game.DefaultConfig()
	cfg.AllowFailedFaceUpPlay = allowFailed
	fx.seedGame(t, store.GameRecord{
		GameID: id, OwnerID: "alice", CurrentPlayerID: "alice", SetupComplete: true, Started: true,
		Players: []store.PlayerRecord{
			{PlayerID: "alice", Username: "Alice", Hand: []rules.Card{}, FaceUp: []rules.Card{{Suit: rules.SuitClubs, Value: 3}}, FaceDown: []rules.Card{{Suit: rules.SuitClubs, Value: 12}}, Ready: boolPtrOf(true)},
			{PlayerID: "bob", Username: "Bob", Hand: []rules.Card{{Suit: rules.SuitHearts, Value: 6}}, FaceUp: []rules.Card{}, FaceDown: []rules.Card{}, Ready: boolPtrOf(true)},
		},
		DiscardPile: []rules.Card{{Suit: rules.SuitHearts, Value: 9}},
		Deck:        []rules.Card{},
		Config:      storeConfig(cfg),
	})
	fx.connect(t, "a-c", id, "alice")
	fx.connect(t, "b-c", id, "bob")
}

func TestWebSocket_failedFaceUpPlay_optionOn_revealsCardAndPicksUp(t *testing.T) {
	fx := newFixture()
	fx.failedFaceUpSetup(t, "FU1", true)

	resp := fx.ws(t, "play", "a-c", `{"action":"play","sessionId":"FU1","selections":[{"source":"FACE_UP","index":0}]}`, nil, "")

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("failed face-up play with the option on must succeed as a pickup, got %d", resp.StatusCode)
	}
	var view stateView
	if err := json.Unmarshal(fx.notifier.messagesTo("b-c")[0].Data, &view); err != nil {
		t.Fatal(err)
	}
	if view.RevealedCard == nil || view.RevealedCard.Value != 3 || view.DiscardCount != 0 {
		t.Fatalf("pickup must reveal the card and empty the pile, got %+v", view)
	}
}

func TestWebSocket_failedFaceUpPlay_optionOff_isRejected(t *testing.T) {
	fx := newFixture()
	fx.failedFaceUpSetup(t, "FU2", false)

	resp := fx.ws(t, "play", "a-c", `{"action":"play","sessionId":"FU2","selections":[{"source":"FACE_UP","index":0}]}`, nil, "")

	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("with the option off the illegal face-up play must be rejected, got %d", resp.StatusCode)
	}
	if rec, _ := fx.games.Get(context.Background(), "FU2"); len(rec.Players[0].FaceUp) != 1 {
		t.Fatal("a rejected play must not change the face-up zone")
	}
}

func TestWebSocket_finishingGame_updatesEloOnce(t *testing.T) {
	// Given alice has one card left and bob holds a card: alice's play ends the game
	fx := newFixture()
	fx.users.seed(store.UserRecord{UserID: "alice", Username: "Alice", EloScore: 1000})
	fx.users.seed(store.UserRecord{UserID: "bob", Username: "Bob", EloScore: 1000})
	fx.seedGame(t, store.GameRecord{
		GameID: "ELO1", OwnerID: "alice", CurrentPlayerID: "alice", SetupComplete: true, Started: true,
		Players: []store.PlayerRecord{
			{PlayerID: "alice", Username: "Alice", Hand: []rules.Card{{Suit: rules.SuitHearts, Value: 7}}, FaceUp: []rules.Card{}, FaceDown: []rules.Card{}, Ready: boolPtrOf(true)},
			{PlayerID: "bob", Username: "Bob", Hand: []rules.Card{{Suit: rules.SuitClubs, Value: 4}}, FaceUp: []rules.Card{}, FaceDown: []rules.Card{}, Ready: boolPtrOf(true)},
		},
		DiscardPile: []rules.Card{{Suit: rules.SuitHearts, Value: 5}},
		Deck:        []rules.Card{},
		Config:      storeConfig(game.DefaultConfig()),
	})
	fx.connect(t, "a-c", "ELO1", "alice")

	// When alice plays her last card
	resp := fx.ws(t, "play", "a-c", `{"action":"play","sessionId":"ELO1","selections":[{"source":"HAND","index":0}]}`, nil, "")

	// Then ratings move by 16 and the game is flagged as rated
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("final play must succeed, got %d", resp.StatusCode)
	}
	alice, _ := fx.users.Get(context.Background(), "alice")
	bob, _ := fx.users.Get(context.Background(), "bob")
	if alice.EloScore != 1016 || bob.EloScore != 984 {
		t.Fatalf("expected 1016/984, got %v/%v", alice.EloScore, bob.EloScore)
	}
	rec, _ := fx.games.Get(context.Background(), "ELO1")
	if !rec.Finished || !rec.EloUpdated || rec.ShitheadID != "bob" {
		t.Fatalf("finished game must be flagged: %+v", rec)
	}
}

func TestWebSocket_staleConnectionIsRemovedOnBroadcast(t *testing.T) {
	fx := newFixture()
	id := fx.lobby(t)
	fx.connect(t, "a-c", id, "alice")
	fx.connect(t, "dead", id, "bob")
	fx.notifier.gone["dead"] = true

	fx.ws(t, "setup", "a-c", `{"action":"setup","sessionId":"`+id+`","setupAction":"announce"}`, nil, "")

	if fx.conns.has("dead") {
		t.Fatal("a gone connection must be removed from the registry")
	}
	if !fx.conns.has("a-c") {
		t.Fatal("live connections must be kept")
	}
}

// ---- authorizer -----------------------------------------------------------

func TestAuthorizer_validTokenAllowsRouteWithContext(t *testing.T) {
	fx := newFixture()
	fx.h.d.Verifier = fakeVerifier{tokens: map[string]auth.Claims{
		"good": {"sub": "user-1", "cognito:username": "alice"},
	}}
	ev := map[string]any{
		"type":                  "REQUEST",
		"methodArn":             "arn:aws:execute-api:eu-central-1:123:abc/$default/$connect",
		"queryStringParameters": map[string]string{"token": "good"},
		"headers":               map[string]string{},
	}

	out, err := fx.h.Handle(context.Background(), rawEvent(t, ev))

	if err != nil {
		t.Fatalf("valid token must be accepted: %v", err)
	}
	resp := out.(events.APIGatewayCustomAuthorizerResponse)
	if resp.PrincipalID != "user-1" || resp.Context["sub"] != "user-1" || resp.Context["username"] != "alice" {
		t.Fatalf("unexpected authorizer response: %+v", resp)
	}
	if resp.PolicyDocument.Statement[0].Effect != "Allow" || resp.PolicyDocument.Statement[0].Resource[0] != ev["methodArn"] {
		t.Fatal("policy must allow exactly the requested method ARN")
	}
}

func TestAuthorizer_bearerHeaderIsAccepted(t *testing.T) {
	fx := newFixture()
	fx.h.d.Verifier = fakeVerifier{tokens: map[string]auth.Claims{"good": {"sub": "u"}}}
	ev := map[string]any{
		"type":      "REQUEST",
		"methodArn": "arn:x",
		"headers":   map[string]string{"Authorization": "Bearer good"},
	}

	if _, err := fx.h.Handle(context.Background(), rawEvent(t, ev)); err != nil {
		t.Fatalf("bearer token must be accepted: %v", err)
	}
}

func TestAuthorizer_missingOrInvalidToken_returnsUnauthorized(t *testing.T) {
	fx := newFixture()
	for _, qs := range []map[string]string{{}, {"token": "bad"}} {
		ev := map[string]any{"type": "REQUEST", "methodArn": "arn:x", "queryStringParameters": qs, "headers": map[string]string{}}

		_, err := fx.h.Handle(context.Background(), rawEvent(t, ev))

		if err == nil || err.Error() != "Unauthorized" {
			t.Fatalf("expected Unauthorized, got %v", err)
		}
	}
}
