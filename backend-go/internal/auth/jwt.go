// Package auth verifies Cognito ID tokens for the WebSocket authorizer and reads
// the claims that API Gateway passes to the handlers.
package auth

import (
	"context"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"
)

// ErrInvalidToken is returned for every token that fails verification.
var ErrInvalidToken = errors.New("invalid token")

// minRefreshInterval limits JWKS re-fetches triggered by unknown key ids.
const minRefreshInterval = 30 * time.Second

// Claims are the verified JWT claims.
type Claims map[string]any

// Verifier checks RS256 Cognito ID tokens against a JWKS document.
type Verifier struct {
	issuer   string
	audience string
	jwksURL  string
	client   *http.Client
	now      func() time.Time

	mu          sync.Mutex
	keys        map[string]*rsa.PublicKey
	lastRefresh time.Time
}

// NewVerifier returns a verifier for the user pool issuer and app client id.
// The JWKS document is fetched lazily and cached in memory.
func NewVerifier(issuer, audience, jwksURL string, client *http.Client) *Verifier {
	if client == nil {
		client = &http.Client{Timeout: 5 * time.Second}
	}
	return &Verifier{issuer: issuer, audience: audience, jwksURL: jwksURL, client: client, now: time.Now}
}

// CognitoIssuer is the issuer URL of a user pool.
func CognitoIssuer(region, userPoolID string) string {
	return fmt.Sprintf("https://cognito-idp.%s.amazonaws.com/%s", region, userPoolID)
}

// Verify parses and checks a token. It requires alg RS256, a known kid, a valid
// signature, the expected issuer and audience, token_use "id", and an unexpired exp.
func (v *Verifier) Verify(ctx context.Context, token string) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, ErrInvalidToken
	}
	header, err := decodeSegment(parts[0])
	if err != nil {
		return nil, ErrInvalidToken
	}
	var h struct {
		Alg string `json:"alg"`
		Kid string `json:"kid"`
	}
	if json.Unmarshal(header, &h) != nil || h.Alg != "RS256" || h.Kid == "" {
		return nil, ErrInvalidToken
	}
	key, err := v.keyFor(ctx, h.Kid)
	if err != nil {
		return nil, err
	}
	if err := verifySignature(key, parts[0]+"."+parts[1], parts[2]); err != nil {
		return nil, ErrInvalidToken
	}
	claims, err := decodeClaims(parts[1])
	if err != nil {
		return nil, ErrInvalidToken
	}
	if err := v.checkClaims(claims); err != nil {
		return nil, err
	}
	return claims, nil
}

func (v *Verifier) checkClaims(claims Claims) error {
	if iss, _ := claims["iss"].(string); iss != v.issuer {
		return ErrInvalidToken
	}
	if aud, _ := claims["aud"].(string); aud != v.audience {
		return ErrInvalidToken
	}
	if use, _ := claims["token_use"].(string); use != "id" {
		return ErrInvalidToken
	}
	exp, ok := claims["exp"].(float64)
	if !ok || v.now().Unix() >= int64(exp) {
		return ErrInvalidToken
	}
	if sub, _ := claims["sub"].(string); sub == "" {
		return ErrInvalidToken
	}
	return nil
}

// keyFor returns the public key for kid. An unknown kid triggers one JWKS refresh,
// rate limited so that bogus tokens cannot force a fetch on every request.
func (v *Verifier) keyFor(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if key, ok := v.keys[kid]; ok {
		return key, nil
	}
	if v.keys != nil && v.now().Sub(v.lastRefresh) < minRefreshInterval {
		return nil, ErrInvalidToken
	}
	if err := v.refreshLocked(ctx); err != nil {
		return nil, err
	}
	if key, ok := v.keys[kid]; ok {
		return key, nil
	}
	return nil, ErrInvalidToken
}

func (v *Verifier) refreshLocked(ctx context.Context) error {
	v.lastRefresh = v.now()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.jwksURL, nil)
	if err != nil {
		return err
	}
	resp, err := v.client.Do(req)
	if err != nil {
		return fmt.Errorf("fetch jwks: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("fetch jwks: status %d", resp.StatusCode)
	}
	var doc struct {
		Keys []struct {
			Kid string `json:"kid"`
			Kty string `json:"kty"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&doc); err != nil {
		return fmt.Errorf("decode jwks: %w", err)
	}
	keys := make(map[string]*rsa.PublicKey, len(doc.Keys))
	for _, k := range doc.Keys {
		if k.Kty != "RSA" || k.Kid == "" {
			continue
		}
		pub, err := rsaKey(k.N, k.E)
		if err != nil {
			continue
		}
		keys[k.Kid] = pub
	}
	v.keys = keys
	return nil
}

func rsaKey(modulus, exponent string) (*rsa.PublicKey, error) {
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
		return nil, ErrInvalidToken
	}
	return &rsa.PublicKey{N: new(big.Int).SetBytes(nBytes), E: int(e.Int64())}, nil
}

func verifySignature(key *rsa.PublicKey, signingInput, signature string) error {
	sig, err := decodeSegment(signature)
	if err != nil {
		return err
	}
	digest := sha256.Sum256([]byte(signingInput))
	return rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], sig)
}

func decodeClaims(segment string) (Claims, error) {
	raw, err := decodeSegment(segment)
	if err != nil {
		return nil, err
	}
	var claims Claims
	if err := json.Unmarshal(raw, &claims); err != nil {
		return nil, err
	}
	return claims, nil
}

// decodeSegment accepts base64url with or without padding.
func decodeSegment(segment string) ([]byte, error) {
	return base64.RawURLEncoding.DecodeString(strings.TrimRight(segment, "="))
}
