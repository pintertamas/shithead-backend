package handler

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"github.com/aws/aws-lambda-go/events"
	"github.com/pintertamas/shithead-backend/backend-go/internal/auth"
	"github.com/pintertamas/shithead-backend/backend-go/internal/store"
)

const (
	defaultLeaderboardLimit = 20
	maxLeaderboardLimit     = 100
	maxNicknamePrefix       = 17
	nicknameSuffixLength    = 6
	unknownName             = "Unknown"
	defaultNickname         = "Player"
)

var nicknamePattern = regexp.MustCompile(`^[\p{L}\p{N}_ -]{2,24}$`)

// leaderboardEntry is the JSON shape the frontend expects.
type leaderboardEntry struct {
	UserID   string  `json:"userId"`
	Username string  `json:"username"`
	EloScore float64 `json:"eloScore"`
}

type profileResponse struct {
	Username      string `json:"username"`
	CanClearGames bool   `json:"canClearGames"`
}

func (h *Handler) readProfile(ctx context.Context, claims auth.Claims) events.APIGatewayProxyResponse {
	sub := claims.Subject()
	if sub == "" {
		return statusOnly(http.StatusUnauthorized)
	}
	profile, err := h.ensureNickname(ctx, sub, claims)
	if err != nil {
		return serverError(err)
	}
	return jsonResponse(http.StatusOK, profileResponse{Username: profile.Username, CanClearGames: claims.IsGameAdmin()})
}

func (h *Handler) updateProfile(ctx context.Context, req events.APIGatewayProxyRequest, claims auth.Claims) events.APIGatewayProxyResponse {
	sub := claims.Subject()
	if sub == "" {
		return statusOnly(http.StatusUnauthorized)
	}
	var body map[string]any
	if !parseBody(req.Body, &body) || body == nil {
		return statusOnly(http.StatusBadRequest)
	}
	name, _ := body["username"].(string)
	name = strings.TrimSpace(name)
	if !nicknamePattern.MatchString(name) {
		return jsonResponse(http.StatusBadRequest, map[string]string{
			"message": "Choose a name between 2 and 24 letters, numbers, spaces, hyphens, or underscores.",
		})
	}
	profile, err := h.ensureNickname(ctx, sub, claims)
	if err != nil {
		return serverError(err)
	}
	reserved, err := h.d.Users.ReserveUsername(ctx, profile, name)
	if err != nil {
		return serverError(err)
	}
	if !reserved {
		return jsonResponse(http.StatusConflict, map[string]string{
			"message": "That nickname is already taken. Please choose another.",
		})
	}
	if err := h.renamePlayer(ctx, sub, name); err != nil {
		return serverError(err)
	}
	return jsonResponse(http.StatusOK, profileResponse{Username: profile.Username, CanClearGames: claims.IsGameAdmin()})
}

// ensureNickname creates the profile lazily and reserves a default nickname for
// users who do not have one yet. Default names come from the token claims.
func (h *Handler) ensureNickname(ctx context.Context, sub string, claims auth.Claims) (*store.UserRecord, error) {
	profile, err := h.d.Users.EnsureProfile(ctx, sub)
	if err != nil {
		return nil, err
	}
	if profile.Username != "" {
		return profile, nil
	}
	nickname := claims.Username()
	if nickname == "" {
		nickname = defaultNickname
	}
	reserved, err := h.d.Users.ReserveUsername(ctx, profile, nickname)
	if err != nil {
		return nil, err
	}
	if reserved {
		return profile, nil
	}
	suffix := sub[max(0, len(sub)-nicknameSuffixLength):]
	runes := []rune(nickname)
	prefix := string(runes[:min(len(runes), maxNicknamePrefix)])
	reserved, err = h.d.Users.ReserveUsername(ctx, profile, prefix+"-"+suffix)
	if err != nil {
		return nil, err
	}
	if !reserved {
		return nil, errors.New("could not reserve a unique default nickname")
	}
	return profile, nil
}

// renamePlayer copies a new nickname into every game the user is seated in.
func (h *Handler) renamePlayer(ctx context.Context, userID, name string) error {
	games, err := h.d.Games.All(ctx)
	if err != nil {
		return err
	}
	for _, record := range games {
		changed := false
		for i := range record.Players {
			if record.Players[i].PlayerID == userID {
				record.Players[i].Username = name
				changed = true
			}
		}
		if changed {
			if err := h.d.Games.Put(ctx, record); err != nil {
				return err
			}
		}
	}
	return nil
}

func (h *Handler) leaderboardTop(ctx context.Context, req events.APIGatewayProxyRequest) events.APIGatewayProxyResponse {
	limit := parseLimit(req.QueryStringParameters["limit"])
	users, err := h.d.Users.TopByElo(ctx, limit)
	if err != nil {
		return serverError(err)
	}
	entries := make([]leaderboardEntry, 0, len(users))
	for _, u := range users {
		entries = append(entries, leaderboardEntry{UserID: u.UserID, Username: u.Username, EloScore: u.EloScore})
	}
	return jsonResponse(http.StatusOK, entries)
}

// parseLimit reads the limit query parameter, clamped to 1..100. Invalid values give the default.
func parseLimit(raw string) int {
	if raw == "" {
		return defaultLeaderboardLimit
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return defaultLeaderboardLimit
	}
	return min(max(value, 1), maxLeaderboardLimit)
}

func (h *Handler) leaderboardSession(ctx context.Context, gameID string) events.APIGatewayProxyResponse {
	record, err := h.d.Games.Get(ctx, gameID)
	if err != nil {
		return serverError(err)
	}
	if record == nil {
		return statusOnly(http.StatusNotFound)
	}
	ids := make([]string, 0, len(record.Players))
	for _, p := range record.Players {
		ids = append(ids, p.PlayerID)
	}
	users, err := h.d.Users.BatchGet(ctx, ids)
	if err != nil {
		return serverError(err)
	}
	byID := make(map[string]store.UserRecord, len(users))
	for _, u := range users {
		byID[u.UserID] = u
	}
	entries := make([]leaderboardEntry, 0, len(ids))
	for _, id := range ids {
		if u, ok := byID[id]; ok {
			entries = append(entries, leaderboardEntry{UserID: id, Username: u.Username, EloScore: u.EloScore})
		} else {
			entries = append(entries, leaderboardEntry{UserID: id, Username: unknownName})
		}
	}
	return jsonResponse(http.StatusOK, entries)
}

// doomsday closes every WebSocket connection and deletes every active game.
// Profiles and ratings are kept. Only members of the game-admin group may call it.
func (h *Handler) doomsday(ctx context.Context, claims auth.Claims) events.APIGatewayProxyResponse {
	if !claims.IsGameAdmin() {
		return jsonResponse(http.StatusForbidden, map[string]string{
			"message": "Administrator access is required for this action.",
		})
	}
	closed, failed, err := h.closeAllConnections(ctx)
	if err != nil {
		return serverError(err)
	}
	deleted, err := h.deleteAllGames(ctx)
	if err != nil {
		return serverError(err)
	}
	return jsonResponse(http.StatusOK, map[string]int{
		"deletedGames":      deleted,
		"closedConnections": closed,
		"failedConnections": failed,
	})
}

func (h *Handler) closeAllConnections(ctx context.Context) (int, int, error) {
	connections, err := h.d.Connections.All(ctx)
	if err != nil {
		return 0, 0, err
	}
	closed, failed := 0, 0
	for _, c := range connections {
		err := h.d.Notifier.Delete(ctx, h.d.ManagementEndpoint, c.ConnectionID)
		switch {
		case err == nil:
			closed++
		case errors.Is(err, ErrConnectionGone):
		default:
			failed++
			slog.Warn("could not close websocket connection", "connection", c.ConnectionID, "error", err)
		}
		if err == nil || errors.Is(err, ErrConnectionGone) {
			if delErr := h.d.Connections.Delete(ctx, c.ConnectionID); delErr != nil {
				return closed, failed, fmt.Errorf("delete connection record: %w", delErr)
			}
		}
	}
	return closed, failed, nil
}

func (h *Handler) deleteAllGames(ctx context.Context) (int, error) {
	games, err := h.d.Games.All(ctx)
	if err != nil {
		return 0, err
	}
	for _, g := range games {
		if err := h.d.Games.Delete(ctx, g.GameID); err != nil {
			return 0, err
		}
	}
	return len(games), nil
}
