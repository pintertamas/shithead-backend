package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// fakeManagementAPI stands in for the WebSocket management endpoint. It answers
// GetConnection with the status configured for each connection id, 200 by default.
type fakeManagementAPI struct {
	srv      *httptest.Server
	statuses map[string]int

	mu       sync.Mutex
	requests []string
	auth     []string
}

func (f *fakeManagementAPI) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.requests = append(f.requests, r.Method+" "+r.URL.Path)
	f.auth = append(f.auth, r.Header.Get("Authorization"))
	id, found := strings.CutPrefix(r.URL.Path, "/$default/@connections/")
	if !found {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	status, configured := f.statuses[id]
	if !configured {
		status = http.StatusOK
	}
	w.WriteHeader(status)
}

// callCount returns how many GetConnection requests the fake has received.
func (f *fakeManagementAPI) callCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.requests)
}

// useManagementAPI starts a fake management endpoint and points the glue at it.
func useManagementAPI(t *testing.T, statuses map[string]int) *fakeManagementAPI {
	t.Helper()
	api := &fakeManagementAPI{statuses: statuses}
	api.srv = httptest.NewServer(api)
	t.Cleanup(api.srv.Close)
	setAWSTestEnv(t, api.srv.URL+"/$default")
	return api
}

// useUnreachableEndpoint points the glue at an address where nothing listens.
func useUnreachableEndpoint(t *testing.T) {
	t.Helper()
	srv := httptest.NewServer(http.NotFoundHandler())
	endpoint := srv.URL + "/$default"
	srv.Close()
	setAWSTestEnv(t, endpoint)
}

// setAWSTestEnv gives the glue the environment a Lambda would: static credentials,
// a region, and no shared config files that could override them.
func setAWSTestEnv(t *testing.T, endpoint string) {
	t.Helper()
	t.Setenv("WS_MANAGEMENT_ENDPOINT", endpoint)
	t.Setenv("AWS_ACCESS_KEY_ID", "AKIDTEST")
	t.Setenv("AWS_SECRET_ACCESS_KEY", "secretTEST")
	t.Setenv("AWS_SESSION_TOKEN", "")
	t.Setenv("AWS_REGION", "eu-central-1")
	t.Setenv("AWS_PROFILE", "")
	t.Setenv("AWS_CONFIG_FILE", filepath.Join(t.TempDir(), "config"))
	t.Setenv("AWS_SHARED_CREDENTIALS_FILE", filepath.Join(t.TempDir(), "credentials"))
}

func TestConnectionCheckerMapsGetConnectionStatus(t *testing.T) {
	tests := []struct {
		name      string
		status    int
		wantState connectionState
		wantErr   bool
	}{
		{name: "200 means open", status: http.StatusOK, wantState: connectionOpen},
		{name: "410 means gone", status: http.StatusGone, wantState: connectionGone},
		{name: "403 is unknown", status: http.StatusForbidden, wantErr: true},
		{name: "500 is unknown", status: http.StatusInternalServerError, wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Given: the management endpoint answers this status for connection c1
			useManagementAPI(t, map[string]int{"c1": tt.status})

			// When
			state, err := newConnectionChecker(context.Background()).check(context.Background(), "c1")

			// Then
			if (err != nil) != tt.wantErr {
				t.Fatalf("err = %v, wantErr %v", err, tt.wantErr)
			}
			if !tt.wantErr && state != tt.wantState {
				t.Fatalf("state = %v, want %v", state, tt.wantState)
			}
		})
	}
}

func TestConnectionCheckerSignsGetConnectionRequest(t *testing.T) {
	// Given
	api := useManagementAPI(t, nil)

	// When
	if _, err := newConnectionChecker(context.Background()).check(context.Background(), "abc="); err != nil {
		t.Fatalf("check: %v", err)
	}

	// Then: a GET to the endpoint path, signed for execute-api in the Lambda's region
	api.mu.Lock()
	defer api.mu.Unlock()
	if got := api.requests[0]; got != "GET /$default/@connections/abc=" {
		t.Fatalf("request = %q, want GET /$default/@connections/abc=", got)
	}
	auth := api.auth[0]
	if !strings.HasPrefix(auth, "AWS4-HMAC-SHA256 Credential=AKIDTEST/") ||
		!strings.Contains(auth, "/eu-central-1/execute-api/aws4_request") {
		t.Fatalf("Authorization = %q, want a SigV4 signature for execute-api in eu-central-1", auth)
	}
}

func TestConnectionCheckerRejectsEmptyIDWithoutCalling(t *testing.T) {
	// Given
	api := useManagementAPI(t, nil)

	// When
	_, err := newConnectionChecker(context.Background()).check(context.Background(), "")

	// Then
	if err == nil {
		t.Fatal("empty connection id was accepted")
	}
	if api.callCount() != 0 {
		t.Fatalf("GetConnection calls = %d, want 0", api.callCount())
	}
}

func TestConnectionCheckerWithoutEndpointIsUnknown(t *testing.T) {
	// Given: no management endpoint configured
	setAWSTestEnv(t, "")

	// When
	_, err := newConnectionChecker(context.Background()).check(context.Background(), "c1")

	// Then
	if err == nil {
		t.Fatal("check succeeded without a management endpoint")
	}
}

func TestConnectionCheckerUnreachableEndpointIsUnknown(t *testing.T) {
	// Given
	useUnreachableEndpoint(t)

	// When
	_, err := newConnectionChecker(context.Background()).check(context.Background(), "c1")

	// Then
	if err == nil {
		t.Fatal("check succeeded against an unreachable endpoint")
	}
}
