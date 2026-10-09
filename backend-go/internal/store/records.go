// Package store maps the game domain onto the existing DynamoDB tables. Attribute
// names match the Java and Python backends so that existing items keep working.
package store

import "github.com/pintertamas/shithead-backend/backend-go/internal/rules"

// GameRecord is one item of the game-sessions table (partition key game_id).
type GameRecord struct {
	GameID          string         `dynamodbav:"game_id"`
	OwnerID         string         `dynamodbav:"user_id,omitempty"`
	Players         []PlayerRecord `dynamodbav:"players"`
	DiscardPile     []rules.Card   `dynamodbav:"discardPile"`
	Deck            []rules.Card   `dynamodbav:"deck"`
	CurrentPlayerID string         `dynamodbav:"currentPlayerId,omitempty"`
	Started         bool           `dynamodbav:"started"`
	Starting        bool           `dynamodbav:"starting"`
	SetupComplete   bool           `dynamodbav:"setupComplete"`
	Finished        bool           `dynamodbav:"finished"`
	EloUpdated      bool           `dynamodbav:"eloUpdated"`
	ShitheadID      string         `dynamodbav:"shitheadId,omitempty"`
	Config          *ConfigRecord  `dynamodbav:"config,omitempty"`
	CreatedAt       string         `dynamodbav:"created_at,omitempty"`
	TTL             int64          `dynamodbav:"ttl,omitempty"`
}

// PlayerRecord is one seat inside a GameRecord. Ready is a pointer so that a
// missing attribute can default to true, as the Java entity did.
type PlayerRecord struct {
	PlayerID string       `dynamodbav:"playerId"`
	Username string       `dynamodbav:"username"`
	Hand     []rules.Card `dynamodbav:"hand"`
	FaceUp   []rules.Card `dynamodbav:"faceUp"`
	FaceDown []rules.Card `dynamodbav:"faceDown"`
	Out      bool         `dynamodbav:"out"`
	Ready    *bool        `dynamodbav:"ready"`
}

// ConfigRecord is the stored game configuration (the "config" map attribute).
type ConfigRecord struct {
	DecksCount     int               `dynamodbav:"decksCount"`
	BurnCount      int               `dynamodbav:"burnCount"`
	FaceDownCount  int               `dynamodbav:"faceDownCount"`
	FaceUpCount    int               `dynamodbav:"faceUpCount"`
	HandCount      int               `dynamodbav:"handCount"`
	AllowMixed     bool              `dynamodbav:"allowMixedHandAndFaceUpWhenDeckEmpty"`
	AllowFailed    bool              `dynamodbav:"allowFailedFaceUpPlay"`
	CardRules      map[string]string `dynamodbav:"cardRules"`
	AlwaysPlayable []int             `dynamodbav:"alwaysPlayable"`
	CanPlayAgain   []int             `dynamodbav:"canPlayAgain"`
}

// UserRecord is one item of the users table (partition key user_id). Nickname
// claim rows share the table but never decode into this type.
type UserRecord struct {
	UserID        string  `dynamodbav:"user_id"`
	Username      string  `dynamodbav:"username,omitempty"`
	EloScore      float64 `dynamodbav:"elo_score"`
	LeaderboardPK string  `dynamodbav:"leaderboard_pk,omitempty"`
}

// ConnectionRecord is one item of the WebSocket connection registry.
type ConnectionRecord struct {
	ConnectionID  string `dynamodbav:"connection_id"`
	TTL           int64  `dynamodbav:"ttl,omitempty"`
	GameSessionID string `dynamodbav:"game_session_id,omitempty"`
	UserID        string `dynamodbav:"user_id,omitempty"`
}
