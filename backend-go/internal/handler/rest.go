package handler

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"

	"github.com/aws/aws-lambda-go/events"
	"github.com/pintertamas/shithead-backend/backend-go/internal/auth"
	"github.com/pintertamas/shithead-backend/backend-go/internal/game"
	"github.com/pintertamas/shithead-backend/backend-go/internal/store"
)

// corsHeaders are sent on every response, including errors and OPTIONS.
var corsHeaders = map[string]string{
	"Access-Control-Allow-Origin":  "*",
	"Access-Control-Allow-Headers": "Content-Type,Authorization",
	"Access-Control-Allow-Methods": "POST,GET,PUT,OPTIONS",
}

func statusOnly(status int) events.APIGatewayProxyResponse {
	return events.APIGatewayProxyResponse{StatusCode: status, Headers: corsHeaders}
}

func jsonResponse(status int, body any) events.APIGatewayProxyResponse {
	data, err := json.Marshal(body)
	if err != nil {
		slog.Error("response serialization failed", "error", err)
		return statusOnly(http.StatusInternalServerError)
	}
	headers := map[string]string{"Content-Type": "application/json"}
	for k, v := range corsHeaders {
		headers[k] = v
	}
	return events.APIGatewayProxyResponse{StatusCode: status, Headers: headers, Body: string(data)}
}

func serverError(err error) events.APIGatewayProxyResponse {
	slog.Error("request failed", "error", err)
	return jsonResponse(http.StatusInternalServerError, map[string]string{"message": "Internal server error"})
}

// normalizePath removes a leading stage segment and any trailing slash.
func normalizePath(path, stage string) string {
	if stage != "" && (path == "/"+stage || strings.HasPrefix(path, "/"+stage+"/")) {
		path = strings.TrimPrefix(path, "/"+stage)
	}
	if len(path) > 1 {
		path = strings.TrimRight(path, "/")
	}
	if path == "" {
		return "/"
	}
	return path
}

// rest routes a REST proxy request. OPTIONS is answered for every path; every
// other route requires the Cognito claims that API Gateway attaches.
func (h *Handler) rest(ctx context.Context, req events.APIGatewayProxyRequest) events.APIGatewayProxyResponse {
	if req.HTTPMethod == http.MethodOptions {
		return statusOnly(http.StatusOK)
	}
	path := normalizePath(req.Path, req.RequestContext.Stage)
	segments := strings.Split(strings.Trim(path, "/"), "/")
	claims := auth.FromAuthorizer(req.RequestContext.Authorizer)
	method := req.HTTPMethod

	switch {
	case method == http.MethodPost && path == "/create-game":
		return h.createGame(ctx, req, claims)
	case method == http.MethodPost && path == "/join-game":
		return h.joinGame(ctx, req, claims)
	case method == http.MethodPost && path == "/leave-game":
		return h.leaveGame(ctx, req, claims)
	case method == http.MethodPost && path == "/start-game":
		return h.startGame(ctx, req, claims)
	case method == http.MethodGet && len(segments) == 2 && segments[0] == "state":
		return h.getState(ctx, segments[1], claims)
	case method == http.MethodGet && path == "/leaderboard/top":
		return h.leaderboardTop(ctx, req)
	case method == http.MethodGet && len(segments) == 3 && segments[0] == "leaderboard" && segments[1] == "session":
		return h.leaderboardSession(ctx, segments[2])
	case method == http.MethodGet && path == "/profile":
		return h.readProfile(ctx, claims)
	case method == http.MethodPut && path == "/profile":
		return h.updateProfile(ctx, req, claims)
	case method == http.MethodPost && path == "/admin/doomsday":
		return h.doomsday(ctx, claims)
	default:
		return statusOnly(http.StatusNotFound)
	}
}

// parseBody decodes a JSON object body into out. It reports false for malformed JSON.
func parseBody(body string, out any) bool {
	if strings.TrimSpace(body) == "" {
		return true
	}
	return json.Unmarshal([]byte(body), out) == nil
}

type sessionRequest struct {
	SessionID string `json:"sessionId"`
	Phase     string `json:"phase"`
}

func (h *Handler) createGame(ctx context.Context, req events.APIGatewayProxyRequest, claims auth.Claims) events.APIGatewayProxyResponse {
	sub := claims.Subject()
	if sub == "" {
		return statusOnly(http.StatusUnauthorized)
	}
	var body struct {
		Config any `json:"config"`
	}
	if !parseBody(req.Body, &body) {
		return jsonResponse(http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
	}
	cfg, err := game.ParseConfig(body.Config)
	if err != nil {
		return jsonResponse(http.StatusBadRequest, map[string]string{"error": "Invalid game configuration"})
	}
	if err := h.cleanupOldSessions(ctx, sub, ""); err != nil {
		return serverError(err)
	}
	gameID, err := h.newGameID(ctx)
	if err != nil {
		return serverError(err)
	}
	username, err := h.displayName(ctx, sub)
	if err != nil {
		return serverError(err)
	}
	record := store.NewGameRecord(gameID, sub, username, cfg, h.d.Now())
	if err := h.d.Games.Put(ctx, record); err != nil {
		return serverError(err)
	}
	return jsonResponse(http.StatusOK, map[string]string{"sessionId": gameID})
}

func (h *Handler) joinGame(ctx context.Context, req events.APIGatewayProxyRequest, claims auth.Claims) events.APIGatewayProxyResponse {
	sub := claims.Subject()
	if sub == "" {
		return statusOnly(http.StatusUnauthorized)
	}
	var body sessionRequest
	if !parseBody(req.Body, &body) || body.SessionID == "" {
		return statusOnly(http.StatusBadRequest)
	}
	record, err := h.d.Games.Get(ctx, body.SessionID)
	if err != nil {
		return serverError(err)
	}
	if record == nil {
		return statusOnly(http.StatusNotFound)
	}
	if record.Started {
		return statusOnly(http.StatusConflict)
	}
	if hasPlayer(*record, sub) {
		return statusOnly(http.StatusOK)
	}
	if err := h.cleanupOldSessions(ctx, sub, body.SessionID); err != nil {
		return serverError(err)
	}
	username, err := h.displayName(ctx, sub)
	if err != nil {
		return serverError(err)
	}
	session := store.FromRecord(*record)
	if err := session.AddPlayer(sub, username); err != nil {
		return statusOnly(http.StatusConflict)
	}
	if err := h.d.Games.Put(ctx, store.ToRecord(session, *record)); err != nil {
		return serverError(err)
	}
	return statusOnly(http.StatusOK)
}

func (h *Handler) leaveGame(ctx context.Context, req events.APIGatewayProxyRequest, claims auth.Claims) events.APIGatewayProxyResponse {
	sub := claims.Subject()
	if sub == "" {
		return statusOnly(http.StatusUnauthorized)
	}
	var body sessionRequest
	if !parseBody(req.Body, &body) || body.SessionID == "" {
		return statusOnly(http.StatusBadRequest)
	}
	record, err := h.d.Games.Get(ctx, body.SessionID)
	if err != nil {
		return serverError(err)
	}
	if record == nil {
		return statusOnly(http.StatusNotFound)
	}
	if record.Started {
		return jsonResponse(http.StatusConflict, map[string]string{"error": "Cannot leave a started game"})
	}
	session := store.FromRecord(*record)
	if err := session.RemovePlayer(sub); err != nil {
		return jsonResponse(http.StatusConflict, map[string]string{"error": err.Error()})
	}
	if len(session.Players) == 0 {
		err = h.d.Games.Delete(ctx, body.SessionID)
	} else {
		err = h.d.Games.Put(ctx, store.ToRecord(session, *record))
	}
	if err != nil {
		return serverError(err)
	}
	return statusOnly(http.StatusOK)
}

func (h *Handler) startGame(ctx context.Context, req events.APIGatewayProxyRequest, claims auth.Claims) events.APIGatewayProxyResponse {
	sub := claims.Subject()
	if sub == "" {
		return statusOnly(http.StatusUnauthorized)
	}
	var body sessionRequest
	if !parseBody(req.Body, &body) || body.SessionID == "" {
		return statusOnly(http.StatusBadRequest)
	}
	record, err := h.d.Games.Get(ctx, body.SessionID)
	if err != nil {
		return serverError(err)
	}
	if record == nil {
		return statusOnly(http.StatusNotFound)
	}
	if sub != record.OwnerID {
		return statusOnly(http.StatusForbidden)
	}
	if len(record.Players) < 2 {
		return statusOnly(http.StatusBadRequest)
	}
	if body.Phase == "prepare" {
		return h.prepareStart(ctx, record)
	}
	if record.Started {
		return statusOnly(http.StatusOK)
	}
	return h.dealGame(ctx, record)
}

// prepareStart marks the game as starting so that the lobby can show the countdown.
func (h *Handler) prepareStart(ctx context.Context, record *store.GameRecord) events.APIGatewayProxyResponse {
	if !record.Started && !record.Starting {
		record.Starting = true
		if err := h.d.Games.Put(ctx, *record); err != nil {
			return serverError(err)
		}
	}
	return jsonResponse(http.StatusOK, map[string]bool{"starting": true})
}

// dealGame deals the cards. A failed deal clears the starting flag.
func (h *Handler) dealGame(ctx context.Context, record *store.GameRecord) events.APIGatewayProxyResponse {
	session := store.FromRecord(*record)
	if err := session.Start(); err != nil {
		if record.Starting {
			record.Starting = false
			if putErr := h.d.Games.Put(ctx, *record); putErr != nil {
				return serverError(putErr)
			}
		}
		return statusOnly(http.StatusConflict)
	}
	updated := store.ToRecord(session, *record)
	updated.Starting = false
	if err := h.d.Games.Put(ctx, updated); err != nil {
		return serverError(err)
	}
	return statusOnly(http.StatusOK)
}

func (h *Handler) getState(ctx context.Context, gameID string, claims auth.Claims) events.APIGatewayProxyResponse {
	sub := claims.Subject()
	if sub == "" {
		return statusOnly(http.StatusUnauthorized)
	}
	record, err := h.d.Games.Get(ctx, gameID)
	if err != nil {
		return serverError(err)
	}
	if record == nil {
		return statusOnly(http.StatusNotFound)
	}
	ratings := h.ratingsFor(ctx, *record)
	return jsonResponse(http.StatusOK, buildView(*record, sub, ratings, nil))
}

// cleanupOldSessions removes the owner from every unstarted game they own.
// The owner role passes to the next seat; a game left empty is deleted.
func (h *Handler) cleanupOldSessions(ctx context.Context, ownerID, excludeGameID string) error {
	owned, err := h.d.Games.ByOwner(ctx, ownerID)
	if err != nil {
		return err
	}
	for _, record := range owned {
		if record.Started || record.GameID == excludeGameID {
			continue
		}
		session := store.FromRecord(record)
		if err := session.RemovePlayer(ownerID); err != nil {
			return err
		}
		if len(session.Players) == 0 {
			err = h.d.Games.Delete(ctx, record.GameID)
		} else {
			err = h.d.Games.Put(ctx, store.ToRecord(session, record))
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// newGameID picks a session code not used by another game.
func (h *Handler) newGameID(ctx context.Context) (string, error) {
	for attempt := 0; attempt < 10; attempt++ {
		id := h.d.NewGameID()
		existing, err := h.d.Games.Get(ctx, id)
		if err != nil {
			return "", err
		}
		if existing == nil {
			return id, nil
		}
	}
	return h.d.NewGameID(), nil
}

// displayName returns the stored nickname, or "Unknown" when there is none.
func (h *Handler) displayName(ctx context.Context, userID string) (string, error) {
	user, err := h.d.Users.Get(ctx, userID)
	if err != nil {
		return "", err
	}
	if user == nil || user.Username == "" {
		return "Unknown", nil
	}
	return user.Username, nil
}

func hasPlayer(record store.GameRecord, userID string) bool {
	for _, p := range record.Players {
		if p.PlayerID == userID {
			return true
		}
	}
	return false
}
