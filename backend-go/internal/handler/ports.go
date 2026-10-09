// Package handler routes Lambda events (REST proxy, WebSocket and the WebSocket
// REQUEST authorizer) to the game and account operations. It depends only on
// the interfaces below so that it can be tested without AWS.
package handler

import (
	"context"
	"errors"
	"time"

	"github.com/pintertamas/shithead-backend/backend-go/internal/auth"
	"github.com/pintertamas/shithead-backend/backend-go/internal/store"
)

// ErrConnectionGone is returned by a Notifier when the client has disconnected.
var ErrConnectionGone = errors.New("connection gone")

// GameRepo persists game sessions.
type GameRepo interface {
	Get(ctx context.Context, gameID string) (*store.GameRecord, error)
	Put(ctx context.Context, record store.GameRecord) error
	Delete(ctx context.Context, gameID string) error
	ByOwner(ctx context.Context, ownerID string) ([]store.GameRecord, error)
	All(ctx context.Context) ([]store.GameRecord, error)
}

// UserRepo persists profiles and ratings.
type UserRepo interface {
	Get(ctx context.Context, userID string) (*store.UserRecord, error)
	EnsureProfile(ctx context.Context, userID string) (*store.UserRecord, error)
	BatchGet(ctx context.Context, userIDs []string) ([]store.UserRecord, error)
	SetElo(ctx context.Context, userID string, elo float64) (bool, error)
	TopByElo(ctx context.Context, limit int) ([]store.UserRecord, error)
	ReserveUsername(ctx context.Context, profile *store.UserRecord, username string) (bool, error)
}

// ConnectionRepo persists the WebSocket connection registry.
type ConnectionRepo interface {
	Put(ctx context.Context, record store.ConnectionRecord) error
	UserID(ctx context.Context, connectionID string) (string, error)
	Delete(ctx context.Context, connectionID string) error
	ForSession(ctx context.Context, gameSessionID string) ([]store.ConnectionRecord, error)
	All(ctx context.Context) ([]store.ConnectionRecord, error)
}

// Notifier pushes data to WebSocket clients through the management API.
type Notifier interface {
	Post(ctx context.Context, endpoint, connectionID string, data []byte) error
	Delete(ctx context.Context, endpoint, connectionID string) error
}

// TokenVerifier validates Cognito ID tokens.
type TokenVerifier interface {
	Verify(ctx context.Context, token string) (auth.Claims, error)
}

// Deps are the collaborators of the Handler.
type Deps struct {
	Games              GameRepo
	Users              UserRepo
	Connections        ConnectionRepo
	Notifier           Notifier
	Verifier           TokenVerifier
	ManagementEndpoint string
	// Now returns the current time; defaults to time.Now.
	Now func() time.Time
	// NewGameID returns a candidate session code; defaults to a random 6-character code.
	NewGameID func() string
}

// Handler dispatches events. It is safe for concurrent use.
type Handler struct {
	d Deps
}

// New creates a handler, filling in defaults for optional dependencies.
func New(d Deps) *Handler {
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.NewGameID == nil {
		d.NewGameID = randomGameID
	}
	return &Handler{d: d}
}

// connectionTTLSeconds is how long a registry row lives before DynamoDB expires it.
const connectionTTLSeconds = 3600
