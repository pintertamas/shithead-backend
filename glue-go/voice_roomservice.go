package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// liveKitHTTPClient bounds each RoomService call, well inside the glue Lambda's 10 s timeout.
var liveKitHTTPClient = &http.Client{Timeout: 5 * time.Second}

// endVoiceRooms ends voice chat for everyone at the limit: it lists the LiveKit rooms
// and deletes each one, which disconnects all of its participants. Only counts are logged.
func (a *App) endVoiceRooms(ctx context.Context, cfg LiveKitConfig) error {
	if cfg.URL == "" || cfg.APIKey == "" || cfg.APISecret == "" {
		return errLiveKitNotConfigured
	}
	token, err := serverAPIToken(cfg, a.now())
	if err != nil {
		return err
	}
	base := liveKitHTTPBase(cfg.URL)
	var listed struct {
		Rooms []struct {
			Name string `json:"name"`
		} `json:"rooms"`
	}
	if err := callRoomService(ctx, base, "ListRooms", token, map[string]any{}, &listed); err != nil {
		return fmt.Errorf("list LiveKit rooms: %w", err)
	}
	failed := 0
	for _, room := range listed.Rooms {
		if err := callRoomService(ctx, base, "DeleteRoom", token, map[string]any{"room": room.Name}, nil); err != nil {
			failed++
		}
	}
	log.Printf("voice limit reached: listed %d rooms, deleted %d, failed %d", len(listed.Rooms), len(listed.Rooms)-failed, failed)
	if failed > 0 {
		return fmt.Errorf("%d LiveKit rooms could not be deleted", failed)
	}
	return nil
}

// serverAPIToken signs the short-lived access token the RoomService accepts. ListRooms
// needs the roomList grant; DeleteRoom needs roomCreate (LiveKit's documented grant for it).
func serverAPIToken(cfg LiveKitConfig, now time.Time) (string, error) {
	return signHS256(cfg.APISecret, map[string]any{
		"iss":   cfg.APIKey,
		"nbf":   now.Add(-time.Minute).Unix(),
		"exp":   now.Add(5 * time.Minute).Unix(),
		"video": map[string]bool{"roomList": true, "roomCreate": true},
	})
}

// callRoomService posts one Twirp request to the RoomService and decodes the JSON
// reply into out when out is not nil.
func callRoomService(ctx context.Context, base, method, token string, body map[string]any, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("encode %s request: %w", method, err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+"/twirp/livekit.RoomService/"+method, bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("build %s request: %w", method, err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := liveKitHTTPClient.Do(req)
	if err != nil {
		return fmt.Errorf("%s: %w", method, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, resp.Body)
		return fmt.Errorf("%s returned HTTP %d", method, resp.StatusCode)
	}
	if out == nil {
		_, _ = io.Copy(io.Discard, resp.Body)
		return nil
	}
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		return fmt.Errorf("decode %s response: %w", method, err)
	}
	return nil
}

// liveKitHTTPBase turns the wss:// URL LiveKit publishes into the https:// base the
// RoomService is served on. Plain http(s) URLs (local test servers) pass through.
func liveKitHTTPBase(rawURL string) string {
	base := strings.TrimSuffix(rawURL, "/")
	switch {
	case strings.HasPrefix(base, "wss://"):
		return "https://" + strings.TrimPrefix(base, "wss://")
	case strings.HasPrefix(base, "ws://"):
		return "http://" + strings.TrimPrefix(base, "ws://")
	}
	return base
}
