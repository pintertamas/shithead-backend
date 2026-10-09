package handler

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"

	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
	"github.com/pintertamas/shithead-backend/backend-go/internal/store"
)

// defaultRating is shown for a player whose profile has no rating.
const defaultRating = 1000.0

// stateView is the GameStateView JSON contract used by the frontend.
type stateView struct {
	SessionID                            string       `json:"sessionId"`
	Started                              bool         `json:"started"`
	Starting                             bool         `json:"starting"`
	SetupComplete                        bool         `json:"setupComplete"`
	Finished                             bool         `json:"finished"`
	CurrentPlayerID                      *string      `json:"currentPlayerId"`
	ShitheadID                           *string      `json:"shitheadId"`
	IsOwner                              bool         `json:"isOwner"`
	DeckCount                            int          `json:"deckCount"`
	AllowMixedHandAndFaceUpWhenDeckEmpty bool         `json:"allowMixedHandAndFaceUpWhenDeckEmpty"`
	AllowFailedFaceUpPlay                bool         `json:"allowFailedFaceUpPlay"`
	RevealedCard                         *rules.Card  `json:"revealedCard"`
	DiscardCount                         int          `json:"discardCount"`
	DiscardPile                          []rules.Card `json:"discardPile"`
	Players                              []playerView `json:"players"`
}

// playerView is one seat as seen by one viewer. Hidden cards are never included.
type playerView struct {
	PlayerID      string       `json:"playerId"`
	Username      string       `json:"username"`
	HandCount     int          `json:"handCount"`
	FaceUp        []rules.Card `json:"faceUp"`
	FaceDownCount int          `json:"faceDownCount"`
	IsYou         bool         `json:"isYou"`
	Hand          []rules.Card `json:"hand"`
	EloScore      float64      `json:"eloScore"`
	Ready         bool         `json:"ready"`
}

// buildView renders the game for one viewer. Only the viewer's hand is included.
func buildView(record store.GameRecord, viewerID string, ratings map[string]float64, revealed *rules.Card) stateView {
	players := make([]playerView, 0, len(record.Players))
	for _, p := range record.Players {
		isYou := viewerID != "" && viewerID == p.PlayerID
		hand := []rules.Card{}
		if isYou {
			hand = nonNilCards(p.Hand)
		}
		rating, ok := ratings[p.PlayerID]
		if !ok {
			rating = defaultRating
		}
		players = append(players, playerView{
			PlayerID:      p.PlayerID,
			Username:      p.Username,
			HandCount:     len(p.Hand),
			FaceUp:        nonNilCards(p.FaceUp),
			FaceDownCount: len(p.FaceDown),
			IsYou:         isYou,
			Hand:          hand,
			EloScore:      rating,
			Ready:         p.Ready == nil || *p.Ready,
		})
	}
	allowMixed, allowFailed := false, false
	if record.Config != nil {
		allowMixed = record.Config.AllowMixed
		allowFailed = record.Config.AllowFailed
	}
	return stateView{
		SessionID:                            record.GameID,
		Started:                              record.Started,
		Starting:                             record.Starting,
		SetupComplete:                        record.SetupComplete,
		Finished:                             record.Finished,
		CurrentPlayerID:                      nullable(record.CurrentPlayerID),
		ShitheadID:                           nullable(record.ShitheadID),
		IsOwner:                              viewerID != "" && viewerID == record.OwnerID,
		DeckCount:                            len(record.Deck),
		AllowMixedHandAndFaceUpWhenDeckEmpty: allowMixed,
		AllowFailedFaceUpPlay:                allowFailed,
		RevealedCard:                         revealed,
		DiscardCount:                         len(record.DiscardPile),
		DiscardPile:                          nonNilCards(record.DiscardPile),
		Players:                              players,
	}
}

func nullable(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func nonNilCards(cards []rules.Card) []rules.Card {
	if cards == nil {
		return []rules.Card{}
	}
	return cards
}

// ratingsFor loads the current ratings of the seated players. A failure is
// logged and treated as "no ratings", so the game view still renders.
func (h *Handler) ratingsFor(ctx context.Context, record store.GameRecord) map[string]float64 {
	ids := make([]string, 0, len(record.Players))
	for _, p := range record.Players {
		ids = append(ids, p.PlayerID)
	}
	ratings := map[string]float64{}
	if len(ids) == 0 {
		return ratings
	}
	users, err := h.d.Users.BatchGet(ctx, ids)
	if err != nil {
		slog.Warn("could not load player ratings", "game", record.GameID, "error", err)
		return map[string]float64{}
	}
	for _, u := range users {
		ratings[u.UserID] = u.EloScore
	}
	return ratings
}

// broadcast sends each subscribed connection its own view of the game.
// Connections that have gone away are removed from the registry.
func (h *Handler) broadcast(ctx context.Context, record store.GameRecord, endpoint string, revealed *rules.Card) {
	connections, err := h.d.Connections.ForSession(ctx, record.GameID)
	if err != nil {
		slog.Error("list session connections failed", "game", record.GameID, "error", err)
		return
	}
	ratings := h.ratingsFor(ctx, record)
	for _, c := range connections {
		data, err := json.Marshal(buildView(record, c.UserID, ratings, revealed))
		if err != nil {
			slog.Error("serialize game state failed", "game", record.GameID, "error", err)
			continue
		}
		err = h.d.Notifier.Post(ctx, endpoint, c.ConnectionID, data)
		switch {
		case err == nil:
		case errors.Is(err, ErrConnectionGone):
			slog.Info("removing stale connection", "connection", c.ConnectionID)
			if delErr := h.d.Connections.Delete(ctx, c.ConnectionID); delErr != nil {
				slog.Error("remove stale connection failed", "connection", c.ConnectionID, "error", delErr)
			}
		default:
			slog.Warn("broadcast to connection failed", "connection", c.ConnectionID, "error", err)
		}
	}
}
