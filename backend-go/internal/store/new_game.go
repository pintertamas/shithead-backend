package store

import (
	"time"

	"github.com/pintertamas/shithead-backend/backend-go/internal/game"
	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

// gameTTLSeconds is the lifetime of a lobby that is never started.
const gameTTLSeconds = 3600

// NewGameRecord builds a fresh lobby with the owner as the only seat. The item
// layout matches the Python create_game Lambda.
func NewGameRecord(gameID, ownerID, username string, cfg game.GameConfig, now time.Time) GameRecord {
	ready := true
	return GameRecord{
		GameID:  gameID,
		OwnerID: ownerID,
		Players: []PlayerRecord{{
			PlayerID: ownerID,
			Username: username,
			Hand:     []rules.Card{},
			FaceUp:   []rules.Card{},
			FaceDown: []rules.Card{},
			Ready:    &ready,
		}},
		DiscardPile:     []rules.Card{},
		Deck:            []rules.Card{},
		CurrentPlayerID: ownerID,
		SetupComplete:   true,
		Config:          configToRecord(cfg),
		CreatedAt:       formatTimestamp(now),
		TTL:             now.Unix() + gameTTLSeconds,
	}
}

// formatTimestamp matches Python's datetime.isoformat() in UTC, which the
// created_at-index GSI and existing items use.
func formatTimestamp(t time.Time) string {
	return t.UTC().Format("2006-01-02T15:04:05.000000") + "+00:00"
}
