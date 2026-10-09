package auth

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

const (
	testIssuer   = "https://cognito-idp.eu-central-1.amazonaws.com/eu-central-1_test"
	testAudience = "client-123"
)

// jwksServer serves the public keys of the given kid→key map and counts fetches.
type jwksServer struct {
	mu      sync.Mutex
	keys    map[string]*rsa.PrivateKey
	fetches int
	server  *httptest.Server
}

func newJWKSServer(t *testing.T, keys map[string]*rsa.PrivateKey) *jwksServer {
	t.Helper()
	s := &jwksServer{keys: keys}
	s.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.fetches++
		var doc struct {
			Keys []map[string]string `json:"keys"`
		}
		for kid, key := range s.keys {
			doc.Keys = append(doc.Keys, map[string]string{
				"kid": kid, "kty": "RSA", "alg": "RS256", "use": "sig",
				"n": b64(key.PublicKey.N.Bytes()),
				"e": b64(big.NewInt(int64(key.PublicKey.E)).Bytes()),
			})
		}
		_ = json.NewEncoder(w).Encode(doc)
	}))
	t.Cleanup(s.server.Close)
	return s
}

func (s *jwksServer) fetchCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.fetches
}

func (s *jwksServer) setKeys(keys map[string]*rsa.PrivateKey) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.keys = keys
}

func b64(data []byte) string {
	return base64.RawURLEncoding.EncodeToString(data)
}

func newKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return key
}

// sign builds an RS256 token with the given header kid and claims.
func sign(t *testing.T, key *rsa.PrivateKey, kid, alg string, claims map[string]any) string {
	t.Helper()
	header, _ := json.Marshal(map[string]string{"alg": alg, "kid": kid, "typ": "JWT"})
	payload, _ := json.Marshal(claims)
	signingInput := b64(header) + "." + b64(payload)
	digest := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return signingInput + "." + b64(sig)
}

func validClaims(now time.Time) map[string]any {
	return map[string]any{
		"sub":              "user-1",
		"iss":              testIssuer,
		"aud":              testAudience,
		"token_use":        "id",
		"exp":              now.Add(time.Hour).Unix(),
		"iat":              now.Unix(),
		"cognito:username": "alice",
	}
}

func newTestVerifier(t *testing.T, server *jwksServer, now time.Time) *Verifier {
	t.Helper()
	v := NewVerifier(testIssuer, testAudience, server.server.URL, server.server.Client())
	v.now = func() time.Time { return now }
	return v
}

func TestVerify_validIdToken_returnsClaims(t *testing.T) {
	// Given a token signed with a key published in the JWKS
	now := time.Now()
	key := newKey(t)
	server := newJWKSServer(t, map[string]*rsa.PrivateKey{"k1": key})
	verifier := newTestVerifier(t, server, now)
	token := sign(t, key, "k1", "RS256", validClaims(now))

	// When
	claims, err := verifier.Verify(context.Background(), token)

	// Then
	if err != nil {
		t.Fatalf("expected valid token, got %v", err)
	}
	if claims.Subject() != "user-1" || claims.Username() != "alice" {
		t.Fatalf("unexpected claims: %v", claims)
	}
}

func TestVerify_rejectsBadTokens(t *testing.T) {
	now := time.Now()
	key := newKey(t)
	other := newKey(t)
	server := newJWKSServer(t, map[string]*rsa.PrivateKey{"k1": key})

	cases := map[string]func() string{
		"wrong audience": func() string {
			c := validClaims(now)
			c["aud"] = "someone-else"
			return sign(t, key, "k1", "RS256", c)
		},
		"wrong issuer": func() string {
			c := validClaims(now)
			c["iss"] = "https://evil.example.com"
			return sign(t, key, "k1", "RS256", c)
		},
		"access token instead of id token": func() string {
			c := validClaims(now)
			c["token_use"] = "access"
			return sign(t, key, "k1", "RS256", c)
		},
		"expired": func() string {
			c := validClaims(now)
			c["exp"] = now.Add(-time.Minute).Unix()
			return sign(t, key, "k1", "RS256", c)
		},
		"signed with another key": func() string {
			return sign(t, other, "k1", "RS256", validClaims(now))
		},
		"alg none": func() string {
			return sign(t, key, "k1", "none", validClaims(now))
		},
		"HS256 algorithm": func() string {
			return sign(t, key, "k1", "HS256", validClaims(now))
		},
		"malformed": func() string {
			return "not-a-jwt"
		},
		"unknown kid": func() string {
			return sign(t, key, "unknown", "RS256", validClaims(now))
		},
	}
	for name, make := range cases {
		t.Run(name, func(t *testing.T) {
			verifier := newTestVerifier(t, server, now)

			_, err := verifier.Verify(context.Background(), make())

			if err == nil {
				t.Fatal("token must be rejected")
			}
		})
	}
}

func TestVerify_jwksCached_fetchedOnceForKnownKids(t *testing.T) {
	now := time.Now()
	key := newKey(t)
	server := newJWKSServer(t, map[string]*rsa.PrivateKey{"k1": key})
	verifier := newTestVerifier(t, server, now)
	token := sign(t, key, "k1", "RS256", validClaims(now))

	for i := 0; i < 3; i++ {
		if _, err := verifier.Verify(context.Background(), token); err != nil {
			t.Fatal(err)
		}
	}

	if got := server.fetchCount(); got != 1 {
		t.Fatalf("JWKS must be cached, fetched %d times", got)
	}
}

func TestVerify_rotatedKey_refetchesJWKSAfterInterval(t *testing.T) {
	// Given a verifier that has cached key k1
	now := time.Now()
	oldKey := newKey(t)
	newKeyPair := newKey(t)
	server := newJWKSServer(t, map[string]*rsa.PrivateKey{"k1": oldKey})
	verifier := newTestVerifier(t, server, now)
	if _, err := verifier.Verify(context.Background(), sign(t, oldKey, "k1", "RS256", validClaims(now))); err != nil {
		t.Fatal(err)
	}

	// When the pool rotates to k2 and a token with k2 arrives after the refresh interval
	server.setKeys(map[string]*rsa.PrivateKey{"k2": newKeyPair})
	later := now.Add(2 * minRefreshInterval)
	verifier.now = func() time.Time { return later }
	token := sign(t, newKeyPair, "k2", "RS256", validClaims(later))

	// Then the new key is fetched and accepted
	if _, err := verifier.Verify(context.Background(), token); err != nil {
		t.Fatalf("rotated key must be accepted after refetch: %v", err)
	}
}

func TestVerify_unknownKid_refetchIsRateLimited(t *testing.T) {
	now := time.Now()
	key := newKey(t)
	server := newJWKSServer(t, map[string]*rsa.PrivateKey{"k1": key})
	verifier := newTestVerifier(t, server, now)
	if _, err := verifier.Verify(context.Background(), sign(t, key, "k1", "RS256", validClaims(now))); err != nil {
		t.Fatal(err)
	}
	before := server.fetchCount()

	for i := 0; i < 5; i++ {
		_, _ = verifier.Verify(context.Background(), sign(t, key, fmt.Sprintf("bogus-%d", i), "RS256", validClaims(now)))
	}

	if got := server.fetchCount(); got != before {
		t.Fatalf("bogus kids must not trigger a refetch within the interval (%d -> %d)", before, got)
	}
}

func TestCognitoIssuer_formatsPoolURL(t *testing.T) {
	if got := CognitoIssuer("eu-central-1", "eu-central-1_abc"); got != "https://cognito-idp.eu-central-1.amazonaws.com/eu-central-1_abc" {
		t.Fatalf("unexpected issuer %s", got)
	}
}

func TestIsGameAdmin_acceptsArrayAndBracketedString(t *testing.T) {
	cases := []struct {
		name   string
		claims Claims
		want   bool
	}{
		{"array with admin", Claims{"cognito:groups": []any{"players", "game-admin"}}, true},
		{"array without admin", Claims{"cognito:groups": []any{"players"}}, false},
		{"bracketed text", Claims{"cognito:groups": "[players, game-admin]"}, true},
		{"missing claim", Claims{}, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := c.claims.IsGameAdmin(); got != c.want {
				t.Fatalf("IsGameAdmin() = %v, want %v", got, c.want)
			}
		})
	}
}

func TestUsername_precedence(t *testing.T) {
	if got := (Claims{"email": "a@b.c", "cognito:username": "cu", "preferred_username": "pu"}).Username(); got != "pu" {
		t.Fatalf("preferred_username must win, got %s", got)
	}
	if got := (Claims{"email": "a@b.c", "cognito:username": "cu"}).Username(); got != "cu" {
		t.Fatalf("cognito:username must come next, got %s", got)
	}
	if got := (Claims{"email": "a@b.c"}).Username(); got != "a@b.c" {
		t.Fatalf("email is the last fallback, got %s", got)
	}
}

func TestFromAuthorizer_readsClaimsMap(t *testing.T) {
	claims := FromAuthorizer(map[string]any{"claims": map[string]any{"sub": "u1"}})
	if claims.Subject() != "u1" {
		t.Fatal("claims must be read from the authorizer context")
	}
	if FromAuthorizer(map[string]any{}) != nil {
		t.Fatal("missing claims must yield nil")
	}
}
