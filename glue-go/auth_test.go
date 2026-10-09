package main

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

const (
	testIssuer   = "https://cognito-idp.eu-central-1.amazonaws.com/pool-1"
	testClientID = "client-123"
)

// jwksServer serves a mutable key set and counts how often it is fetched.
type jwksServer struct {
	*httptest.Server
	mu       sync.Mutex
	keys     []jwkEntry
	requests int
}

type jwkEntry struct {
	kid string
	key *rsa.PublicKey
}

func newJWKSServer(t *testing.T, keys ...jwkEntry) *jwksServer {
	t.Helper()
	js := &jwksServer{keys: keys}
	js.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		js.mu.Lock()
		defer js.mu.Unlock()
		js.requests++
		doc := map[string]any{"keys": []map[string]string{}}
		entries := make([]map[string]string, 0, len(js.keys))
		for _, k := range js.keys {
			entries = append(entries, map[string]string{
				"kid": k.kid,
				"kty": "RSA",
				"alg": "RS256",
				"use": "sig",
				"n":   base64.RawURLEncoding.EncodeToString(k.key.N.Bytes()),
				"e":   base64.RawURLEncoding.EncodeToString(bigEndian(k.key.E)),
			})
		}
		doc["keys"] = entries
		_ = json.NewEncoder(w).Encode(doc)
	}))
	t.Cleanup(js.Close)
	return js
}

func (js *jwksServer) setKeys(keys ...jwkEntry) {
	js.mu.Lock()
	defer js.mu.Unlock()
	js.keys = keys
}

func (js *jwksServer) fetchCount() int {
	js.mu.Lock()
	defer js.mu.Unlock()
	return js.requests
}

func bigEndian(e int) []byte {
	buf := make([]byte, 8)
	binary.BigEndian.PutUint64(buf, uint64(e))
	for len(buf) > 1 && buf[0] == 0 {
		buf = buf[1:]
	}
	return buf
}

var (
	keyOnce sync.Once
	keyA    *rsa.PrivateKey
	keyB    *rsa.PrivateKey
)

// testKeys returns two RSA keys, generated once per test binary.
func testKeys(t *testing.T) (*rsa.PrivateKey, *rsa.PrivateKey) {
	t.Helper()
	keyOnce.Do(func() {
		var err error
		if keyA, err = rsa.GenerateKey(rand.Reader, 2048); err != nil {
			panic(err)
		}
		if keyB, err = rsa.GenerateKey(rand.Reader, 2048); err != nil {
			panic(err)
		}
	})
	return keyA, keyB
}

func b64(data []byte) string {
	return base64.RawURLEncoding.EncodeToString(data)
}

// signToken produces an RS256 JWT with the given header kid and claims.
func signToken(t *testing.T, key *rsa.PrivateKey, kid string, claims map[string]any) string {
	t.Helper()
	header, err := json.Marshal(map[string]string{"alg": "RS256", "kid": kid, "typ": "JWT"})
	if err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatal(err)
	}
	signingInput := b64(header) + "." + b64(payload)
	digest := sha256.Sum256([]byte(signingInput))
	signature, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return signingInput + "." + b64(signature)
}

func validClaims(now time.Time) map[string]any {
	return map[string]any{
		"sub":              "user-42",
		"iss":              testIssuer,
		"aud":              testClientID,
		"token_use":        "id",
		"exp":              now.Add(time.Hour).Unix(),
		"cognito:username": "alice",
	}
}

// newTestVerifier points a verifier at the fake JWKS server with the fixed test clock.
func newTestVerifier(js *jwksServer, now time.Time) *Verifier {
	v := newVerifier(testIssuer, testClientID, js.URL, js.Client(), func() time.Time { return now })
	v.cooldown = 0
	return v
}

func TestVerifyAcceptsValidIDToken(t *testing.T) {
	keyA, _ := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})
	v := newTestVerifier(js, testNow)

	claims, err := v.Verify(context.Background(), signToken(t, keyA, "kid-a", validClaims(testNow)))
	if err != nil {
		t.Fatalf("valid token rejected: %v", err)
	}
	if claims.Subject != "user-42" || claims.Username != "alice" {
		t.Fatalf("unexpected claims: %+v", claims)
	}
}

func TestVerifyRejectsBadTokens(t *testing.T) {
	keyA, keyB := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})

	tests := map[string]struct {
		token string
	}{
		"expired": {
			token: signToken(t, keyA, "kid-a", withClaim(validClaims(testNow), "exp", testNow.Add(-time.Minute).Unix())),
		},
		"wrong audience": {
			token: signToken(t, keyA, "kid-a", withClaim(validClaims(testNow), "aud", "other-app")),
		},
		"wrong issuer": {
			token: signToken(t, keyA, "kid-a", withClaim(validClaims(testNow), "iss", "https://evil.example")),
		},
		"access token instead of ID token": {
			token: signToken(t, keyA, "kid-a", withClaim(validClaims(testNow), "token_use", "access")),
		},
		"unknown kid": {
			token: signToken(t, keyA, "kid-missing", validClaims(testNow)),
		},
		"signed with another key": {
			token: signToken(t, keyB, "kid-a", validClaims(testNow)),
		},
		"not a JWT": {
			token: "abc.def",
		},
	}
	for name, tc := range tests {
		t.Run(name, func(t *testing.T) {
			v := newTestVerifier(js, testNow)
			if _, err := v.Verify(context.Background(), tc.token); !errors.Is(err, errInvalidToken) {
				t.Fatalf("expected invalid token, got %v", err)
			}
		})
	}
}

func TestVerifyRefetchesJWKSForUnknownKid(t *testing.T) {
	keyA, keyB := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})
	v := newTestVerifier(js, testNow)

	if _, err := v.Verify(context.Background(), signToken(t, keyA, "kid-a", validClaims(testNow))); err != nil {
		t.Fatal(err)
	}
	// Cognito rotates its signing key; the new kid is unknown until the JWKS is refetched.
	js.setKeys(jwkEntry{kid: "kid-a", key: &keyA.PublicKey}, jwkEntry{kid: "kid-b", key: &keyB.PublicKey})
	if _, err := v.Verify(context.Background(), signToken(t, keyB, "kid-b", validClaims(testNow))); err != nil {
		t.Fatalf("rotated key should verify after refetch: %v", err)
	}
	if got := js.fetchCount(); got != 2 {
		t.Fatalf("expected 2 JWKS fetches (initial + refetch), got %d", got)
	}
}

func TestVerifyDoesNotRefetchWithinCooldown(t *testing.T) {
	keyA, _ := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})
	v := newTestVerifier(js, testNow)
	v.cooldown = time.Hour

	for i := 0; i < 5; i++ {
		_, _ = v.Verify(context.Background(), signToken(t, keyA, "forged-kid", validClaims(testNow)))
	}
	if got := js.fetchCount(); got != 1 {
		t.Fatalf("forged kids must not trigger repeated fetches, got %d", got)
	}
}

// --- authorizer -------------------------------------------------------------

func authorizerEvent(token string, headers map[string]string) map[string]any {
	return map[string]any{
		"type":                  "REQUEST",
		"methodArn":             "arn:aws:execute-api:eu-central-1:123:abc/prod/$connect",
		"headers":               headers,
		"queryStringParameters": map[string]string{"token": token},
	}
}

func newAuthorizerApp(t *testing.T, db Dynamo, js *jwksServer) *App {
	t.Helper()
	app := newTestApp(db)
	app.verifier = newTestVerifier(js, testNow)
	return app
}

func TestAuthorizerAllowsValidUser(t *testing.T) {
	keyA, _ := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})
	app := newAuthorizerApp(t, newFakeDynamo(), js)

	token := signToken(t, keyA, "kid-a", validClaims(testNow))
	result, err := app.Handle(context.Background(), mustRaw(t, authorizerEvent(token, nil)))
	if err != nil {
		t.Fatal(err)
	}
	resp := result.(events.APIGatewayCustomAuthorizerResponse)
	if resp.PolicyDocument.Statement[0].Effect != "Allow" || resp.PrincipalID != "user-42" {
		t.Fatalf("expected Allow for user-42, got %+v", resp)
	}
}

func TestAuthorizerDeniesBlockedUser(t *testing.T) {
	keyA, _ := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})
	db := newFakeDynamo()
	db.seed(testUsers, map[string]types.AttributeValue{
		"user_id": s("user-42"),
		"blocked": &types.AttributeValueMemberBOOL{Value: true},
	})
	app := newAuthorizerApp(t, db, js)

	token := signToken(t, keyA, "kid-a", validClaims(testNow))
	result, err := app.Handle(context.Background(), mustRaw(t, authorizerEvent(token, nil)))
	if err != nil {
		t.Fatal(err)
	}
	resp := result.(events.APIGatewayCustomAuthorizerResponse)
	if resp.PolicyDocument.Statement[0].Effect != "Deny" {
		t.Fatalf("blocked user must be denied, got %+v", resp)
	}
}

func TestAuthorizerAllowsUserWhoseBlockIsFalse(t *testing.T) {
	keyA, _ := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})
	db := newFakeDynamo()
	db.seed(testUsers, map[string]types.AttributeValue{
		"user_id": s("user-42"),
		"blocked": &types.AttributeValueMemberBOOL{Value: false},
	})
	app := newAuthorizerApp(t, db, js)

	result, err := app.Handle(context.Background(), mustRaw(t, authorizerEvent(signToken(t, keyA, "kid-a", validClaims(testNow)), nil)))
	if err != nil {
		t.Fatal(err)
	}
	if effect := result.(events.APIGatewayCustomAuthorizerResponse).PolicyDocument.Statement[0].Effect; effect != "Allow" {
		t.Fatalf("blocked=false must be allowed, got %s", effect)
	}
}

func TestAuthorizerRejectsMissingOrInvalidToken(t *testing.T) {
	keyA, _ := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})
	app := newAuthorizerApp(t, newFakeDynamo(), js)

	cases := map[string]map[string]any{
		"no token": {
			"type":      "REQUEST",
			"methodArn": "arn:aws:execute-api:eu-central-1:123:abc/prod/$connect",
		},
		"bad token": authorizerEvent("not-a-token", nil),
	}
	for name, event := range cases {
		t.Run(name, func(t *testing.T) {
			_, err := app.Handle(context.Background(), mustRaw(t, event))
			if err == nil || err.Error() != "Unauthorized" {
				t.Fatalf("expected Unauthorized, got %v", err)
			}
		})
	}
}

func TestAuthorizerAcceptsBearerHeader(t *testing.T) {
	keyA, _ := testKeys(t)
	js := newJWKSServer(t, jwkEntry{kid: "kid-a", key: &keyA.PublicKey})
	app := newAuthorizerApp(t, newFakeDynamo(), js)

	token := signToken(t, keyA, "kid-a", validClaims(testNow))
	event := map[string]any{
		"type":      "REQUEST",
		"methodArn": "arn:aws:execute-api:eu-central-1:123:abc/prod/$connect",
		"headers":   map[string]string{"Authorization": "Bearer " + token},
	}
	if _, err := app.Handle(context.Background(), mustRaw(t, event)); err != nil {
		t.Fatalf("bearer token in Authorization header should be accepted: %v", err)
	}
}

func withClaim(claims map[string]any, key string, value any) map[string]any {
	claims[key] = value
	return claims
}
