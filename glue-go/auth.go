package main

import (
	"context"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-lambda-go/events"
)

var (
	// errUnauthorized becomes a 401 at API Gateway when returned from a REQUEST authorizer.
	errUnauthorized = errors.New("Unauthorized")
	errInvalidToken = errors.New("invalid token")
)

const (
	jwksFetchTimeout = 5 * time.Second
	// unknownKidCooldown limits JWKS refetches triggered by unknown key ids, so
	// that forged tokens cannot make the function hammer the Cognito endpoint.
	unknownKidCooldown = time.Minute
)

// Claims are the ID token claims the authorizer uses.
type Claims struct {
	Subject  string `json:"sub"`
	Issuer   string `json:"iss"`
	Audience string `json:"aud"`
	TokenUse string `json:"token_use"`
	Expires  int64  `json:"exp"`
	Username string `json:"cognito:username"`
}

// Verifier checks RS256-signed Cognito ID tokens against the pool's JWKS.
type Verifier struct {
	issuer   string
	audience string
	jwksURL  string
	client   *http.Client
	now      func() time.Time
	cooldown time.Duration

	mu         sync.Mutex
	keys       map[string]*rsa.PublicKey
	lastFetch  time.Time
	hasFetched bool
}

// NewVerifier builds a verifier for the given Cognito user pool and app client.
// No network call is made until the first token is checked.
func NewVerifier(region, poolID, clientID string) *Verifier {
	issuer := fmt.Sprintf("https://cognito-idp.%s.amazonaws.com/%s", region, poolID)
	return newVerifier(issuer, clientID, issuer+"/.well-known/jwks.json",
		&http.Client{Timeout: jwksFetchTimeout}, time.Now)
}

func newVerifier(issuer, audience, jwksURL string, client *http.Client, now func() time.Time) *Verifier {
	return &Verifier{
		issuer:   issuer,
		audience: audience,
		jwksURL:  jwksURL,
		client:   client,
		now:      now,
		cooldown: unknownKidCooldown,
		keys:     map[string]*rsa.PublicKey{},
	}
}

// Verify checks the signature, issuer, audience, token use and expiry of an ID token.
func (v *Verifier) Verify(ctx context.Context, token string) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return Claims{}, errInvalidToken
	}
	header, err := decodeSegment(parts[0])
	if err != nil {
		return Claims{}, errInvalidToken
	}
	var head struct {
		Alg string `json:"alg"`
		Kid string `json:"kid"`
	}
	if err := json.Unmarshal(header, &head); err != nil || head.Alg != "RS256" {
		return Claims{}, errInvalidToken
	}
	key, err := v.keyFor(ctx, head.Kid)
	if err != nil {
		return Claims{}, err
	}
	signature, err := decodeSegment(parts[2])
	if err != nil {
		return Claims{}, errInvalidToken
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if err := rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], signature); err != nil {
		return Claims{}, errInvalidToken
	}
	return v.checkClaims(parts[1])
}

func (v *Verifier) checkClaims(payloadSegment string) (Claims, error) {
	payload, err := decodeSegment(payloadSegment)
	if err != nil {
		return Claims{}, errInvalidToken
	}
	var claims Claims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return Claims{}, errInvalidToken
	}
	switch {
	case claims.Subject == "":
		return Claims{}, errInvalidToken
	case claims.Issuer != v.issuer:
		return Claims{}, errInvalidToken
	case claims.Audience != v.audience:
		return Claims{}, errInvalidToken
	case claims.TokenUse != "id":
		return Claims{}, errInvalidToken
	case v.now().Unix() >= claims.Expires:
		return Claims{}, errInvalidToken
	}
	return claims, nil
}

// keyFor returns the public key for kid, refetching the JWKS when the kid is unknown.
func (v *Verifier) keyFor(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if !v.hasFetched {
		if err := v.refreshLocked(ctx); err != nil {
			return nil, err
		}
	}
	if key, ok := v.keys[kid]; ok {
		return key, nil
	}
	if v.now().Sub(v.lastFetch) < v.cooldown {
		return nil, errInvalidToken
	}
	if err := v.refreshLocked(ctx); err != nil {
		return nil, err
	}
	key, ok := v.keys[kid]
	if !ok {
		return nil, errInvalidToken
	}
	return key, nil
}

// refreshLocked downloads the JWKS. The caller must hold v.mu.
func (v *Verifier) refreshLocked(ctx context.Context) error {
	v.lastFetch = v.now()
	v.hasFetched = true
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.jwksURL, nil)
	if err != nil {
		return fmt.Errorf("build JWKS request: %w", err)
	}
	resp, err := v.client.Do(req)
	if err != nil {
		return fmt.Errorf("fetch JWKS: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("fetch JWKS: status %d", resp.StatusCode)
	}
	keys, err := parseJWKS(resp.Body)
	if err != nil {
		return err
	}
	v.keys = keys
	return nil
}

func parseJWKS(body io.Reader) (map[string]*rsa.PublicKey, error) {
	var doc struct {
		Keys []struct {
			Kid string `json:"kid"`
			Kty string `json:"kty"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := json.NewDecoder(body).Decode(&doc); err != nil {
		return nil, fmt.Errorf("decode JWKS: %w", err)
	}
	keys := make(map[string]*rsa.PublicKey, len(doc.Keys))
	for _, k := range doc.Keys {
		if k.Kty != "RSA" {
			continue
		}
		pub, err := rsaPublicKey(k.N, k.E)
		if err != nil {
			return nil, fmt.Errorf("JWKS key %s: %w", k.Kid, err)
		}
		keys[k.Kid] = pub
	}
	return keys, nil
}

func rsaPublicKey(modulus, exponent string) (*rsa.PublicKey, error) {
	nBytes, err := decodeSegment(modulus)
	if err != nil {
		return nil, err
	}
	eBytes, err := decodeSegment(exponent)
	if err != nil {
		return nil, err
	}
	e := new(big.Int).SetBytes(eBytes)
	if !e.IsInt64() {
		return nil, errors.New("exponent too large")
	}
	return &rsa.PublicKey{N: new(big.Int).SetBytes(nBytes), E: int(e.Int64())}, nil
}

// decodeSegment decodes a base64url JWT segment, with or without padding.
func decodeSegment(segment string) ([]byte, error) {
	return base64.RawURLEncoding.DecodeString(strings.TrimRight(segment, "="))
}

// authorize is the WebSocket REQUEST authorizer. It accepts the token from the
// ?token= query string or from a bearer value in the headers, and denies
// users whose profile has blocked = true.
func (a *App) authorize(ctx context.Context, raw json.RawMessage) (any, error) {
	var req events.APIGatewayCustomAuthorizerRequestTypeRequest
	if err := json.Unmarshal(raw, &req); err != nil {
		return nil, errUnauthorized
	}
	token := tokenFromRequest(req.QueryStringParameters, req.Headers)
	if token == "" {
		log.Print("authorizer: no token provided")
		return nil, errUnauthorized
	}
	if a.verifier == nil {
		return nil, errors.New("authorizer is not configured with a Cognito user pool")
	}
	claims, err := a.verifier.Verify(ctx, token)
	if err != nil {
		if errors.Is(err, errInvalidToken) {
			log.Print("authorizer: token rejected")
			return nil, errUnauthorized
		}
		return nil, err
	}
	blocked, err := a.isBlocked(ctx, claims.Subject)
	if err != nil {
		return nil, err
	}
	effect := "Allow"
	if blocked {
		effect = "Deny"
	}
	return events.APIGatewayCustomAuthorizerResponse{
		PrincipalID: claims.Subject,
		PolicyDocument: events.APIGatewayCustomAuthorizerPolicy{
			Version: "2012-10-17",
			Statement: []events.IAMPolicyStatement{{
				Action:   []string{"execute-api:Invoke"},
				Effect:   effect,
				Resource: []string{req.MethodArn},
			}},
		},
		Context: map[string]interface{}{
			"username": claims.Username,
			"sub":      claims.Subject,
		},
	}, nil
}

func (a *App) isBlocked(ctx context.Context, userID string) (bool, error) {
	item, err := a.getItem(ctx, a.settings.UsersTable, "user_id", userID)
	if err != nil {
		return false, err
	}
	return boolAttr(item, "blocked"), nil
}

// tokenFromRequest prefers ?token=, then a "Bearer <token>" value in
// Sec-WebSocket-Protocol or Authorization (header names compared case-insensitively).
func tokenFromRequest(query map[string]string, headers map[string]string) string {
	if token := query["token"]; token != "" {
		return token
	}
	value := headerValue(headers, "sec-websocket-protocol")
	if value == "" {
		value = headerValue(headers, "authorization")
	}
	parts := strings.Fields(value)
	if len(parts) == 2 && strings.EqualFold(parts[0], "bearer") {
		return parts[1]
	}
	return ""
}

func headerValue(headers map[string]string, name string) string {
	for key, value := range headers {
		if strings.EqualFold(key, name) {
			return value
		}
	}
	return ""
}
