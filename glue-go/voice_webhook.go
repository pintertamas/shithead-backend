package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strconv"
	"strings"
	"time"

	"github.com/aws/aws-lambda-go/events"
)

// liveKitWebhookPath is the REST route LiveKit posts room events to.
const liveKitWebhookPath = "/livekit/webhook"

var (
	errLiveKitNotConfigured = errors.New("LiveKit credentials are not configured")
	errWebhookUnsigned      = errors.New("webhook has no Authorization header")
	errWebhookIssuer        = errors.New("webhook issuer is not the LiveKit API key")
	errWebhookBodyHash      = errors.New("webhook body does not match the signed hash")
)

// isLiveKitWebhook reports whether a REST proxy event is for the LiveKit webhook route.
func isLiveKitWebhook(probe eventProbe) bool {
	return probe.Path == liveKitWebhookPath || probe.Resource == liveKitWebhookPath
}

// liveKitWebhookEvent is the part of a LiveKit webhook payload the usage counter reads.
type liveKitWebhookEvent struct {
	Event     string      `json:"event"`
	CreatedAt unixSeconds `json:"createdAt"`
	Room      struct {
		Name string `json:"name"`
	} `json:"room"`
	Participant struct {
		Identity string `json:"identity"`
	} `json:"participant"`
}

// unixSeconds is a createdAt value. LiveKit sends it as a number, while protobuf JSON
// writes int64 as a quoted string, so both forms are accepted.
type unixSeconds int64

// UnmarshalJSON parses a bare or quoted integer; null reads as zero.
func (s *unixSeconds) UnmarshalJSON(data []byte) error {
	text := strings.Trim(string(data), `"`)
	if text == "" || text == "null" {
		*s = 0
		return nil
	}
	value, err := strconv.ParseInt(text, 10, 64)
	if err != nil {
		return fmt.Errorf("createdAt: %w", err)
	}
	*s = unixSeconds(value)
	return nil
}

// handleLiveKitWebhook receives LiveKit room events and keeps the monthly usage counter.
// The route has no Cognito authorizer: the signature in the Authorization header is the
// authentication. An unsigned or badly signed request gets 401 and nothing is written.
func (a *App) handleLiveKitWebhook(ctx context.Context, raw json.RawMessage) (any, error) {
	var req events.APIGatewayProxyRequest
	if err := json.Unmarshal(raw, &req); err != nil {
		return nil, fmt.Errorf("decode webhook request: %w", err)
	}
	body, err := webhookBody(req)
	if err != nil {
		return jsonResponse(400, map[string]string{"message": "Invalid webhook body"})
	}
	cfg := liveKitConfigFromEnv()
	if err := verifyLiveKitWebhook(headerValue(req.Headers, "Authorization"), body, cfg, a.now()); err != nil {
		log.Printf("LiveKit webhook rejected: %v", err)
		return jsonResponse(401, map[string]string{"message": "Invalid webhook signature"})
	}
	var event liveKitWebhookEvent
	if err := json.Unmarshal(body, &event); err != nil {
		return jsonResponse(400, map[string]string{"message": "Invalid webhook body"})
	}
	if err := a.applyVoiceEvent(ctx, cfg, event); err != nil {
		return nil, err
	}
	return jsonResponse(200, map[string]string{"status": "ok"})
}

// webhookBody returns the raw request body exactly as LiveKit signed it.
func webhookBody(req events.APIGatewayProxyRequest) ([]byte, error) {
	if !req.IsBase64Encoded {
		return []byte(req.Body), nil
	}
	return base64.StdEncoding.DecodeString(req.Body)
}

// verifyLiveKitWebhook checks a webhook the way LiveKit's receiver does: the
// Authorization header is an HS256 JWT signed with the API secret, its issuer is the
// API key, and its sha256 claim is the base64 SHA-256 of the raw body. An empty API
// key or secret rejects every request.
func verifyLiveKitWebhook(authorization string, body []byte, cfg LiveKitConfig, now time.Time) error {
	if cfg.APIKey == "" || cfg.APISecret == "" {
		return errLiveKitNotConfigured
	}
	token := strings.TrimPrefix(strings.TrimSpace(authorization), "Bearer ")
	if token == "" {
		return errWebhookUnsigned
	}
	claims, err := parseHS256(token, cfg.APISecret, now)
	if err != nil {
		return err
	}
	if claims.Issuer != cfg.APIKey {
		return errWebhookIssuer
	}
	digest := sha256.Sum256(body)
	want := base64.StdEncoding.EncodeToString(digest[:])
	if subtle.ConstantTimeCompare([]byte(claims.Sha256), []byte(want)) != 1 {
		return errWebhookBodyHash
	}
	return nil
}

// applyVoiceEvent updates the open-session rows and the monthly total for one event.
// A join opens a session; a leave closes it and adds its rounded minutes. When a leave
// brings the month to the limit, every room is deleted. A failed delete is logged, not
// returned: the total is already right, and the next leave repeats the attempt.
func (a *App) applyVoiceEvent(ctx context.Context, cfg LiveKitConfig, event liveKitWebhookEvent) error {
	room, identity := event.Room.Name, event.Participant.Identity
	if room == "" || identity == "" {
		return nil
	}
	at := int64(event.CreatedAt)
	if at == 0 {
		at = a.now().Unix()
	}
	switch event.Event {
	case "participant_joined":
		return a.openVoiceSession(ctx, room, identity, at)
	case "participant_left":
		total, counted, err := a.recordVoiceLeave(ctx, room, identity, at)
		if err != nil {
			return err
		}
		if counted && total >= voiceMonthlyLimitMinutes {
			if err := a.endVoiceRooms(ctx, cfg); err != nil {
				log.Printf("voice limit reached but ending rooms was incomplete: %v", err)
			}
		}
	}
	return nil
}
