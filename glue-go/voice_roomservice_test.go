package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// fakeRoomService answers the two RoomService calls the glue makes. For each call it
// checks the bearer token's signature and video grant, and it records the deletions.
type fakeRoomService struct {
	secret string
	rooms  []string

	mu      sync.Mutex
	calls   map[string]int
	deleted []string
}

// newFakeRoomService starts the fake on an httptest server, closed when the test ends.
func newFakeRoomService(t *testing.T, secret string, rooms ...string) (*fakeRoomService, string) {
	t.Helper()
	fake := &fakeRoomService{secret: secret, rooms: rooms, calls: map[string]int{}}
	server := httptest.NewServer(fake)
	t.Cleanup(server.Close)
	return fake, server.URL
}

func (f *fakeRoomService) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	method := strings.TrimPrefix(r.URL.Path, "/twirp/livekit.RoomService/")
	f.mu.Lock()
	f.calls[method]++
	f.mu.Unlock()

	grants, ok := bearerGrants(r.Header.Get("Authorization"), f.secret)
	if !ok {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	switch method {
	case "ListRooms":
		if !grants["roomList"] {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		rooms := make([]map[string]string, 0, len(f.rooms))
		for _, name := range f.rooms {
			rooms = append(rooms, map[string]string{"name": name})
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"rooms": rooms})
	case "DeleteRoom":
		if !grants["roomCreate"] {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		var req struct {
			Room string `json:"room"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		f.mu.Lock()
		f.deleted = append(f.deleted, req.Room)
		f.mu.Unlock()
		_, _ = w.Write([]byte("{}"))
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

// count returns how many calls of one RoomService method were received.
func (f *fakeRoomService) count(method string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls[method]
}

// deletedRooms returns the names passed to DeleteRoom.
func (f *fakeRoomService) deletedRooms() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.deleted...)
}

// bearerGrants verifies an HS256 bearer token against secret and returns its video grants.
// It checks the signature and issuer only: the clock is fixed in these tests.
func bearerGrants(authorization, secret string) (map[string]bool, bool) {
	parts := strings.Split(strings.TrimPrefix(authorization, "Bearer "), ".")
	if len(parts) != 3 {
		return nil, false
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(parts[0] + "." + parts[1]))
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || !hmac.Equal(signature, mac.Sum(nil)) {
		return nil, false
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, false
	}
	var claims struct {
		Issuer string          `json:"iss"`
		Video  map[string]bool `json:"video"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil || claims.Issuer != testLiveKitKey {
		return nil, false
	}
	return claims.Video, true
}

func TestLiveKitLimitEndsRoomsOnlyWhenTotalReachesLimit(t *testing.T) {
	// Given 4998 minutes already used this month and two live LiveKit rooms
	fake, url := newFakeRoomService(t, testLiveKitSecret, "ROOM01", "ROOM02")
	setLiveKitEnv(t, url, testLiveKitKey, testLiveKitSecret)
	db := newFakeDynamo()
	seedVoiceUsage(db, testNow, 4998)
	app := newVoiceTestApp(db)

	// When one participant's one-minute connection ends, bringing the total to 4999
	joined := testNow.Unix()
	deliverWebhook(t, app, roomEventBody(t, "participant_joined", "ROOM03", "player-1", joined))
	deliverWebhook(t, app, roomEventBody(t, "participant_left", "ROOM03", "player-1", joined+60))

	// Then the total is 4999 and no RoomService call is made
	if got := usageMinutes(db, testNow); got != 4999 {
		t.Fatalf("total should be 4999, got %d", got)
	}
	if fake.count("ListRooms") != 0 || fake.count("DeleteRoom") != 0 {
		t.Fatalf("nothing may be ended at 4999: ListRooms=%d DeleteRoom=%d", fake.count("ListRooms"), fake.count("DeleteRoom"))
	}

	// When another one-minute connection ends, bringing the total to 5000
	deliverWebhook(t, app, roomEventBody(t, "participant_joined", "ROOM03", "player-2", joined))
	resp := deliverWebhook(t, app, roomEventBody(t, "participant_left", "ROOM03", "player-2", joined+60))

	// Then the rooms are listed and each one is deleted, and the webhook still answers 200
	if got := usageMinutes(db, testNow); got != 5000 {
		t.Fatalf("total should be 5000, got %d", got)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("webhook should answer 200 after ending rooms, got %d", resp.StatusCode)
	}
	if fake.count("ListRooms") != 1 {
		t.Fatalf("ListRooms should be called once, got %d", fake.count("ListRooms"))
	}
	deleted := fake.deletedRooms()
	if len(deleted) != 2 || deleted[0] != "ROOM01" || deleted[1] != "ROOM02" {
		t.Fatalf("both listed rooms should be deleted, got %v", deleted)
	}
}

func TestLiveKitHTTPBaseConvertsWebSocketURLs(t *testing.T) {
	cases := map[string]string{
		"wss://project.livekit.cloud":   "https://project.livekit.cloud",
		"wss://project.livekit.cloud/":  "https://project.livekit.cloud",
		"ws://127.0.0.1:7880":           "http://127.0.0.1:7880",
		"https://project.livekit.cloud": "https://project.livekit.cloud",
	}
	for raw, want := range cases {
		t.Run(raw, func(t *testing.T) {
			// Given a LiveKit URL as configured
			// When it is turned into an HTTP base
			got := liveKitHTTPBase(raw)
			// Then the RoomService is reached over http(s)
			if got != want {
				t.Fatalf("liveKitHTTPBase(%q) = %q, want %q", raw, got, want)
			}
		})
	}
}
