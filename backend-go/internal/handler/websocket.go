package handler

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/aws/aws-lambda-go/events"
	"github.com/pintertamas/shithead-backend/backend-go/internal/elo"
	"github.com/pintertamas/shithead-backend/backend-go/internal/game"
	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
	"github.com/pintertamas/shithead-backend/backend-go/internal/store"
)

const (
	setupActionAnnounce = "announce"
	setupActionSwap     = "swap"
	setupActionReady    = "ready"
	selectionFaceDown   = game.SourceFaceDown
	selectionFaceUp     = game.SourceFaceUp
)

// playMessage is the body of the play and setup actions.
type playMessage struct {
	Action      string               `json:"action"`
	SessionID   string               `json:"sessionId"`
	Cards       []rules.Card         `json:"cards"`
	Selections  []game.CardSelection `json:"selections"`
	SetupAction string               `json:"setupAction"`
	HandIndex   *int                 `json:"handIndex"`
	FaceUpIndex *int                 `json:"faceUpIndex"`
}

// pickupMessage is the body of the pickup action.
type pickupMessage struct {
	SessionID string `json:"sessionId"`
}

// errorMessage is pushed to the sender when an action is rejected.
type errorMessage struct {
	Type    string `json:"type"`
	Status  int    `json:"status"`
	Message string `json:"message"`
}

func (h *Handler) connect(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) events.APIGatewayProxyResponse {
	record := store.ConnectionRecord{
		ConnectionID:  req.RequestContext.ConnectionID,
		TTL:           h.d.Now().Unix() + connectionTTLSeconds,
		GameSessionID: req.QueryStringParameters["game_session_id"],
		UserID:        stringFrom(authorizerMap(req), "sub"),
	}
	if err := h.d.Connections.Put(ctx, record); err != nil {
		slog.Error("register connection failed", "error", err)
		return statusOnly(http.StatusInternalServerError)
	}
	return statusOnly(http.StatusOK)
}

func (h *Handler) disconnect(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) events.APIGatewayProxyResponse {
	if err := h.d.Connections.Delete(ctx, req.RequestContext.ConnectionID); err != nil {
		slog.Error("remove connection failed", "error", err)
		return statusOnly(http.StatusInternalServerError)
	}
	return statusOnly(http.StatusOK)
}

// play handles the "play" route: a card play, a blind flip or a play by selection.
func (h *Handler) play(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) events.APIGatewayProxyResponse {
	var msg playMessage
	if json.Unmarshal([]byte(req.Body), &msg) != nil {
		return h.wsError(ctx, req, http.StatusBadRequest, "Couldn't read the play request. Please try again.")
	}
	record, userID, errResp := h.loadPlayer(ctx, req, msg.SessionID)
	if errResp != nil {
		return *errResp
	}
	if userID != record.CurrentPlayerID {
		return h.wsError(ctx, req, http.StatusBadRequest, "It is not your turn.")
	}
	revealed := revealedCard(msg, *record, userID)
	session := store.FromRecord(*record)
	var result game.PlayResult
	if len(msg.Selections) == 0 {
		result = session.PlayCards(msg.Cards)
	} else {
		result = session.PlaySelections(msg.Selections)
	}
	if result == game.ResultInvalid {
		return h.wsError(ctx, req, http.StatusBadRequest,
			"That play can't be made right now. Check that it's your turn and the cards are allowed.")
	}
	updated := store.ToRecord(session, *record)
	if err := h.d.Games.Put(ctx, updated); err != nil {
		slog.Error("save play failed", "game", record.GameID, "error", err)
		return statusOnly(http.StatusInternalServerError)
	}
	if session.Finished && !record.EloUpdated && h.updateElo(ctx, session) {
		updated.EloUpdated = true
		if err := h.d.Games.Put(ctx, updated); err != nil {
			slog.Error("save elo flag failed", "game", record.GameID, "error", err)
		}
	}
	var reveal *rules.Card
	if result == game.ResultPickup {
		reveal = revealed
	}
	h.broadcast(ctx, updated, endpointOf(req), reveal)
	return statusOnly(http.StatusOK)
}

// setup handles the "setup" route: the owner's start announcement, a card swap or readiness.
func (h *Handler) setup(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) events.APIGatewayProxyResponse {
	var msg playMessage
	if json.Unmarshal([]byte(req.Body), &msg) != nil {
		return h.wsError(ctx, req, http.StatusBadRequest, "Couldn't read the play request. Please try again.")
	}
	record, userID, errResp := h.loadPlayer(ctx, req, msg.SessionID)
	if errResp != nil {
		return *errResp
	}
	if msg.SetupAction == setupActionAnnounce {
		return h.announce(ctx, req, record, userID)
	}
	session := store.FromRecord(*record)
	accepted := false
	switch {
	case msg.SetupAction == setupActionSwap && msg.HandIndex != nil && msg.FaceUpIndex != nil:
		accepted = session.SwapStartingCards(userID, *msg.HandIndex, *msg.FaceUpIndex)
	case msg.SetupAction == setupActionReady:
		accepted = session.MarkReady(userID)
	}
	if !accepted {
		return h.wsError(ctx, req, http.StatusBadRequest, "That setup action is no longer available.")
	}
	updated := store.ToRecord(session, *record)
	if err := h.d.Games.Put(ctx, updated); err != nil {
		slog.Error("save setup failed", "game", record.GameID, "error", err)
		return statusOnly(http.StatusInternalServerError)
	}
	h.broadcast(ctx, updated, endpointOf(req), nil)
	return statusOnly(http.StatusOK)
}

// announce marks the game as starting so every client shows the countdown.
func (h *Handler) announce(ctx context.Context, req events.APIGatewayWebsocketProxyRequest,
	record *store.GameRecord, userID string) events.APIGatewayProxyResponse {
	if userID != record.OwnerID {
		return h.wsError(ctx, req, http.StatusForbidden, "Only the game owner can start the game.")
	}
	if record.Started {
		return h.wsError(ctx, req, http.StatusConflict, "The game has already started.")
	}
	if !record.Starting {
		record.Starting = true
		if err := h.d.Games.Put(ctx, *record); err != nil {
			slog.Error("save announcement failed", "game", record.GameID, "error", err)
			return statusOnly(http.StatusInternalServerError)
		}
	}
	h.broadcast(ctx, *record, endpointOf(req), nil)
	return statusOnly(http.StatusOK)
}

// pickup handles the "pickup" route: the current player takes the discard pile.
func (h *Handler) pickup(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) events.APIGatewayProxyResponse {
	var msg pickupMessage
	if json.Unmarshal([]byte(req.Body), &msg) != nil {
		return h.wsError(ctx, req, http.StatusBadRequest, "Couldn't read the pickup request. Please try again.")
	}
	if msg.SessionID == "" {
		return h.wsError(ctx, req, http.StatusNotFound, "This game session no longer exists.")
	}
	record, err := h.d.Games.Get(ctx, msg.SessionID)
	if err != nil {
		slog.Error("load game failed", "game", msg.SessionID, "error", err)
		return statusOnly(http.StatusInternalServerError)
	}
	if record == nil {
		return h.wsError(ctx, req, http.StatusNotFound, "This game session no longer exists.")
	}
	userID, err := h.d.Connections.UserID(ctx, req.RequestContext.ConnectionID)
	if err != nil {
		slog.Error("load connection failed", "error", err)
		return statusOnly(http.StatusInternalServerError)
	}
	if userID == "" || userID != record.CurrentPlayerID {
		return h.wsError(ctx, req, http.StatusBadRequest, "It is not your turn.")
	}
	session := store.FromRecord(*record)
	if session.PickupPile() == game.ResultInvalid {
		return h.wsError(ctx, req, http.StatusBadRequest,
			"You can't pick up the pile right now. It may be empty or not your turn.")
	}
	updated := store.ToRecord(session, *record)
	if err := h.d.Games.Put(ctx, updated); err != nil {
		slog.Error("save pickup failed", "game", record.GameID, "error", err)
		return statusOnly(http.StatusInternalServerError)
	}
	h.broadcast(ctx, updated, endpointOf(req), nil)
	return statusOnly(http.StatusOK)
}

// loadPlayer loads the game for a WebSocket action and checks that the sender
// has joined it. On failure it returns the response to send back.
func (h *Handler) loadPlayer(ctx context.Context, req events.APIGatewayWebsocketProxyRequest,
	sessionID string) (*store.GameRecord, string, *events.APIGatewayProxyResponse) {
	fail := func(resp events.APIGatewayProxyResponse) (*store.GameRecord, string, *events.APIGatewayProxyResponse) {
		return nil, "", &resp
	}
	if sessionID == "" {
		resp := h.wsError(ctx, req, http.StatusNotFound, "This game session no longer exists.")
		return fail(resp)
	}
	record, err := h.d.Games.Get(ctx, sessionID)
	if err != nil {
		slog.Error("load game failed", "game", sessionID, "error", err)
		return fail(statusOnly(http.StatusInternalServerError))
	}
	if record == nil {
		return fail(h.wsError(ctx, req, http.StatusNotFound, "This game session no longer exists."))
	}
	userID, err := h.d.Connections.UserID(ctx, req.RequestContext.ConnectionID)
	if err != nil {
		slog.Error("load connection failed", "error", err)
		return fail(statusOnly(http.StatusInternalServerError))
	}
	if userID == "" || !hasPlayer(*record, userID) {
		return fail(h.wsError(ctx, req, http.StatusForbidden, "Join this game before sending actions."))
	}
	return record, userID, nil
}

// revealedCard returns the card to show when a blind flip (single face-down) or
// a face-up play is attempted. It is only shown when the result is a pickup.
func revealedCard(msg playMessage, record store.GameRecord, userID string) *rules.Card {
	if len(msg.Selections) == 0 {
		return nil
	}
	first := msg.Selections[0]
	for _, p := range record.Players {
		if p.PlayerID != userID {
			continue
		}
		switch {
		case first.Source == selectionFaceDown && len(msg.Selections) == 1:
			return cardAt(p.FaceDown, first.Index)
		case first.Source == selectionFaceUp:
			return cardAt(p.FaceUp, first.Index)
		}
	}
	return nil
}

func cardAt(cards []rules.Card, index int) *rules.Card {
	if index < 0 || index >= len(cards) {
		return nil
	}
	card := cards[index]
	return &card
}

// updateElo applies the Elo change once a game has finished. It reports whether
// the ratings were written.
func (h *Handler) updateElo(ctx context.Context, session *game.Session) bool {
	results := make(map[string]float64, len(session.Players))
	ids := make([]string, 0, len(session.Players))
	for _, p := range session.Players {
		ids = append(ids, p.PlayerID)
		results[p.PlayerID] = 1
		if p.PlayerID == session.ShitheadID {
			results[p.PlayerID] = 0
		}
	}
	profiles, err := h.d.Users.BatchGet(ctx, ids)
	if err != nil {
		slog.Error("load profiles for elo failed", "game", session.ID, "error", err)
		return false
	}
	current := make(map[string]float64, len(profiles))
	for _, u := range profiles {
		current[u.UserID] = u.EloScore
	}
	if len(current) < 2 {
		slog.Warn("skipping elo update: fewer than two profiles", "game", session.ID)
		return false
	}
	for id, rating := range elo.UpdateRatings(current, results) {
		if _, err := h.d.Users.SetElo(ctx, id, rating); err != nil {
			slog.Error("elo update failed", "game", session.ID, "error", err)
			return false
		}
	}
	return true
}

// wsError pushes an error message to the sender and returns the status code for the route.
func (h *Handler) wsError(ctx context.Context, req events.APIGatewayWebsocketProxyRequest, status int, message string) events.APIGatewayProxyResponse {
	data, err := json.Marshal(errorMessage{Type: "error", Status: status, Message: message})
	if err == nil {
		err = h.d.Notifier.Post(ctx, endpointOf(req), req.RequestContext.ConnectionID, data)
	}
	if err != nil {
		slog.Error("send websocket error failed", "connection", req.RequestContext.ConnectionID, "error", err)
	}
	return statusOnly(status)
}

func authorizerMap(req events.APIGatewayWebsocketProxyRequest) map[string]any {
	if m, ok := req.RequestContext.Authorizer.(map[string]any); ok {
		return m
	}
	return map[string]any{}
}
