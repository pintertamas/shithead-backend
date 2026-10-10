package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

var (
	errMalformedToken = errors.New("malformed token")
	errTokenAlgorithm = errors.New("token algorithm must be HS256")
	errTokenSignature = errors.New("token signature does not match")
	errTokenTiming    = errors.New("token is expired or not yet valid")
)

// liveKitTokenClaims are the claims of a LiveKit-signed JWT the glue reads. A webhook
// token names the API key as issuer and carries the base64 SHA-256 of the body.
type liveKitTokenClaims struct {
	Issuer    string `json:"iss"`
	Sha256    string `json:"sha256"`
	NotBefore *int64 `json:"nbf"`
	Expires   *int64 `json:"exp"`
}

// signHS256 returns a compact JWT signed with HS256. LiveKit server API tokens use this format.
func signHS256(secret string, claims map[string]any) (string, error) {
	header, err := json.Marshal(map[string]string{"alg": "HS256", "typ": "JWT"})
	if err != nil {
		return "", fmt.Errorf("encode token header: %w", err)
	}
	payload, err := json.Marshal(claims)
	if err != nil {
		return "", fmt.Errorf("encode token claims: %w", err)
	}
	signingInput := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(payload)
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(macHS256(secret, signingInput)), nil
}

// parseHS256 verifies a compact JWT against secret and returns its LiveKit claims.
// Only HS256 is accepted (so "none" is refused), an empty secret never verifies, and
// exp and nbf are enforced when present.
func parseHS256(token, secret string, now time.Time) (liveKitTokenClaims, error) {
	var claims liveKitTokenClaims
	if secret == "" {
		return claims, errTokenSignature
	}
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return claims, errMalformedToken
	}
	headerJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return claims, errMalformedToken
	}
	var header struct {
		Alg string `json:"alg"`
	}
	if err := json.Unmarshal(headerJSON, &header); err != nil {
		return claims, errMalformedToken
	}
	if header.Alg != "HS256" {
		return claims, errTokenAlgorithm
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return claims, errMalformedToken
	}
	if !hmac.Equal(signature, macHS256(secret, parts[0]+"."+parts[1])) {
		return claims, errTokenSignature
	}
	payloadJSON, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return claims, errMalformedToken
	}
	if err := json.Unmarshal(payloadJSON, &claims); err != nil {
		return claims, errMalformedToken
	}
	unix := now.Unix()
	if claims.Expires != nil && unix >= *claims.Expires {
		return claims, errTokenTiming
	}
	if claims.NotBefore != nil && unix < *claims.NotBefore {
		return claims, errTokenTiming
	}
	return claims, nil
}

// macHS256 returns the HMAC-SHA256 of data under secret.
func macHS256(secret, data string) []byte {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(data))
	return mac.Sum(nil)
}
