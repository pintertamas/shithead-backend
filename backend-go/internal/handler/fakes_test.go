package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/pintertamas/shithead-backend/backend-go/internal/auth"
	"github.com/pintertamas/shithead-backend/backend-go/internal/store"
)

// clone copies a value through JSON so that stored state never shares slices
// with the handler's working copies (mirrors a real DynamoDB round trip).
func clone[T any](v T) T {
	data, _ := json.Marshal(v)
	var out T
	_ = json.Unmarshal(data, &out)
	return out
}

type fakeGames struct {
	mu    sync.Mutex
	items map[string]store.GameRecord
}

func newFakeGames() *fakeGames { return &fakeGames{items: map[string]store.GameRecord{}} }

func (g *fakeGames) Get(_ context.Context, id string) (*store.GameRecord, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	rec, ok := g.items[id]
	if !ok {
		return nil, nil
	}
	c := clone(rec)
	return &c, nil
}

func (g *fakeGames) Put(_ context.Context, rec store.GameRecord) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.items[rec.GameID] = clone(rec)
	return nil
}

func (g *fakeGames) Delete(_ context.Context, id string) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.items, id)
	return nil
}

func (g *fakeGames) ByOwner(_ context.Context, owner string) ([]store.GameRecord, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	var out []store.GameRecord
	for _, rec := range g.items {
		if rec.OwnerID == owner {
			out = append(out, clone(rec))
		}
	}
	return out, nil
}

func (g *fakeGames) All(_ context.Context) ([]store.GameRecord, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	var out []store.GameRecord
	for _, rec := range g.items {
		out = append(out, clone(rec))
	}
	return out, nil
}

type fakeUsers struct {
	mu        sync.Mutex
	items     map[string]store.UserRecord
	lastLimit int
}

func newFakeUsers() *fakeUsers { return &fakeUsers{items: map[string]store.UserRecord{}} }

func (u *fakeUsers) Get(_ context.Context, id string) (*store.UserRecord, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	rec, ok := u.items[id]
	if !ok {
		return nil, nil
	}
	return &rec, nil
}

func (u *fakeUsers) EnsureProfile(_ context.Context, id string) (*store.UserRecord, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	rec, ok := u.items[id]
	if !ok {
		rec = store.UserRecord{UserID: id, EloScore: 1000, LeaderboardPK: store.LeaderboardPartition}
		u.items[id] = rec
	}
	return &rec, nil
}

func (u *fakeUsers) BatchGet(_ context.Context, ids []string) ([]store.UserRecord, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	var out []store.UserRecord
	for _, id := range ids {
		if rec, ok := u.items[id]; ok {
			out = append(out, rec)
		}
	}
	return out, nil
}

func (u *fakeUsers) SetElo(_ context.Context, id string, elo float64) (bool, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	rec, ok := u.items[id]
	if !ok {
		return false, nil
	}
	rec.EloScore = elo
	u.items[id] = rec
	return true, nil
}

func (u *fakeUsers) TopByElo(_ context.Context, limit int) ([]store.UserRecord, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.lastLimit = limit
	var out []store.UserRecord
	for _, rec := range u.items {
		out = append(out, rec)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].EloScore > out[j].EloScore })
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

// ReserveUsername rejects a nickname another profile already uses, ignoring case.
func (u *fakeUsers) ReserveUsername(_ context.Context, profile *store.UserRecord, name string) (bool, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	want := store.NormalizeUsername(name)
	for id, other := range u.items {
		if id != profile.UserID && other.Username != "" && store.NormalizeUsername(other.Username) == want {
			return false, nil
		}
	}
	rec := u.items[profile.UserID]
	rec.UserID = profile.UserID
	rec.Username = name
	u.items[profile.UserID] = rec
	profile.Username = name
	return true, nil
}

func (u *fakeUsers) seed(rec store.UserRecord) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.items[rec.UserID] = rec
}

type fakeConns struct {
	mu    sync.Mutex
	items map[string]store.ConnectionRecord
}

func newFakeConns() *fakeConns { return &fakeConns{items: map[string]store.ConnectionRecord{}} }

func (c *fakeConns) Put(_ context.Context, rec store.ConnectionRecord) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items[rec.ConnectionID] = rec
	return nil
}

func (c *fakeConns) UserID(_ context.Context, id string) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.items[id].UserID, nil
}

func (c *fakeConns) Delete(_ context.Context, id string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.items, id)
	return nil
}

func (c *fakeConns) ForSession(_ context.Context, sessionID string) ([]store.ConnectionRecord, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out []store.ConnectionRecord
	for _, rec := range c.items {
		if rec.GameSessionID == sessionID {
			out = append(out, rec)
		}
	}
	return out, nil
}

func (c *fakeConns) All(_ context.Context) ([]store.ConnectionRecord, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out []store.ConnectionRecord
	for _, rec := range c.items {
		out = append(out, rec)
	}
	return out, nil
}

func (c *fakeConns) has(id string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	_, ok := c.items[id]
	return ok
}

type sentMessage struct {
	Endpoint     string
	ConnectionID string
	Data         []byte
}

// fakeNotifier records every message. Connections in gone return ErrConnectionGone.
type fakeNotifier struct {
	mu      sync.Mutex
	sent    []sentMessage
	deleted []string
	gone    map[string]bool
}

func newFakeNotifier() *fakeNotifier { return &fakeNotifier{gone: map[string]bool{}} }

func (n *fakeNotifier) Post(_ context.Context, endpoint, connectionID string, data []byte) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.gone[connectionID] {
		return ErrConnectionGone
	}
	n.sent = append(n.sent, sentMessage{Endpoint: endpoint, ConnectionID: connectionID, Data: data})
	return nil
}

func (n *fakeNotifier) Delete(_ context.Context, _, connectionID string) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.gone[connectionID] {
		return ErrConnectionGone
	}
	if strings.HasPrefix(connectionID, "fail-") {
		return errors.New("throttled")
	}
	n.deleted = append(n.deleted, connectionID)
	return nil
}

func (n *fakeNotifier) messagesTo(connectionID string) []sentMessage {
	n.mu.Lock()
	defer n.mu.Unlock()
	var out []sentMessage
	for _, m := range n.sent {
		if m.ConnectionID == connectionID {
			out = append(out, m)
		}
	}
	return out
}

func (n *fakeNotifier) reset() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.sent = nil
}

// fakeVerifier accepts only the tokens it knows about.
type fakeVerifier struct {
	tokens map[string]auth.Claims
}

func (v fakeVerifier) Verify(_ context.Context, token string) (auth.Claims, error) {
	claims, ok := v.tokens[token]
	if !ok {
		return nil, auth.ErrInvalidToken
	}
	return claims, nil
}

// fixture wires the handler to the fakes.
type fixture struct {
	h        *Handler
	games    *fakeGames
	users    *fakeUsers
	conns    *fakeConns
	notifier *fakeNotifier
	now      time.Time
}

const testEndpoint = "https://api.example.com/$default"

func newFixture() *fixture {
	fx := &fixture{
		games:    newFakeGames(),
		users:    newFakeUsers(),
		conns:    newFakeConns(),
		notifier: newFakeNotifier(),
		now:      time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC),
	}
	counter := 0
	fx.h = New(Deps{
		Games:              fx.games,
		Users:              fx.users,
		Connections:        fx.conns,
		Notifier:           fx.notifier,
		Verifier:           fakeVerifier{tokens: map[string]auth.Claims{}},
		ManagementEndpoint: testEndpoint,
		Now:                func() time.Time { return fx.now },
		NewGameID: func() string {
			counter++
			return fmt.Sprintf("GAME%02d", counter)
		},
	})
	return fx
}

func claimsFor(sub, name string) map[string]any {
	return map[string]any{"sub": sub, "preferred_username": name}
}

func adminClaims(sub string) map[string]any {
	return map[string]any{"sub": sub, "cognito:groups": []any{"game-admin"}}
}
