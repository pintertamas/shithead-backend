package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"

	"github.com/aws/aws-lambda-go/events"
)

// Test-only LiveKit credentials. They are not real keys and are never used outside tests.
const (
	testLiveKitKey    = "APIglueTestKey"
	testLiveKitSecret = "glue-test-only-secret-not-a-real-key"
	// testLiveKitURL is never dialled by tests that do not reach the kill path.
	testLiveKitURL = "http://127.0.0.1:1"
)

// setLiveKitEnv points the glue at a LiveKit configuration for one test.
func setLiveKitEnv(t *testing.T, url, key, secret string) {
	t.Helper()
	t.Setenv("LIVEKIT_URL", url)
	t.Setenv("LIVEKIT_API_KEY", key)
	t.Setenv("LIVEKIT_API_SECRET", secret)
}

// webhookClaims are the claims LiveKit signs a webhook with, valid around testNow.
func webhookClaims(issuer string, body []byte) map[string]any {
	digest := sha256.Sum256(body)
	return map[string]any{
		"iss":    issuer,
		"sha256": base64.StdEncoding.EncodeToString(digest[:]),
		"nbf":    testNow.Add(-time.Minute).Unix(),
		"exp":    testNow.Add(time.Minute).Unix(),
	}
}

// signedWebhookToken signs claims with secret. It uses its own encoder rather than
// signHS256, so the tests check the wire format and not the code's own round trip.
func signedWebhookToken(t *testing.T, secret string, claims map[string]any) string {
	t.Helper()
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatal(err)
	}
	input := header + "." + base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(input))
	return input + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// unsignedNoneToken is a JWT that claims alg none and has an empty signature.
func unsignedNoneToken(t *testing.T, claims map[string]any) string {
	t.Helper()
	payload, err := json.Marshal(claims)
	if err != nil {
		t.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none","typ":"JWT"}`)) + "." +
		base64.RawURLEncoding.EncodeToString(payload) + "."
}

// webhookRequest is the REST proxy event API Gateway forwards for a LiveKit POST.
// It carries no requestContext authorizer, because this route has no Cognito check.
func webhookRequest(body, authorization string) map[string]any {
	return map[string]any{
		"httpMethod": "POST",
		"path":       liveKitWebhookPath,
		"resource":   liveKitWebhookPath,
		"headers": map[string]string{
			"Authorization": authorization,
			"Content-Type":  "application/webhook+json",
		},
		"body": body,
	}
}

// deliverWebhook signs body with the test secret and runs it through the glue.
func deliverWebhook(t *testing.T, app *App, body string) events.APIGatewayProxyResponse {
	t.Helper()
	token := signedWebhookToken(t, testLiveKitSecret, webhookClaims(testLiveKitKey, []byte(body)))
	return proxyResponse(t, mustHandle(t, app, mustRaw(t, webhookRequest(body, token))))
}

// roomEventBody is a LiveKit participant webhook body. createdAt is passed through as
// given, so tests can send it as a number or as a quoted string.
func roomEventBody(t *testing.T, event, room, identity string, createdAt any) string {
	t.Helper()
	data, err := json.Marshal(map[string]any{
		"event":       event,
		"id":          "EV_test",
		"createdAt":   createdAt,
		"room":        map[string]any{"name": room},
		"participant": map[string]any{"identity": identity},
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

// mutations counts the DynamoDB writes made so far.
func mutations(db *fakeDynamo) int {
	return db.writes("PutItem") + db.writes("UpdateItem") + db.writes("DeleteItem")
}

func TestLiveKitWebhookAcceptsValidSignature(t *testing.T) {
	// Given LiveKit is configured and a signed join event
	setLiveKitEnv(t, testLiveKitURL, testLiveKitKey, testLiveKitSecret)
	db := newFakeDynamo()
	app := newVoiceTestApp(db)
	body := roomEventBody(t, "participant_joined", "ROOM01", "player-1", testNow.Unix())

	// When it is posted to the REST route, which has no Cognito authorizer
	resp := deliverWebhook(t, app, body)

	// Then it is accepted and the participant's connection is opened
	if resp.StatusCode != 200 {
		t.Fatalf("valid webhook should be 200, got %d: %s", resp.StatusCode, resp.Body)
	}
	if db.item(testUsers, voiceOpenKey("ROOM01", "player-1")) == nil {
		t.Fatal("join should open a voice session row")
	}
}

func TestLiveKitWebhookRejectsBadSignaturesWithoutWriting(t *testing.T) {
	body := roomEventBody(t, "participant_joined", "ROOM01", "player-1", testNow.Unix())
	otherBody := roomEventBody(t, "participant_joined", "ROOM02", "player-2", testNow.Unix())
	expired := webhookClaims(testLiveKitKey, []byte(body))
	expired["exp"] = testNow.Add(-time.Second).Unix()
	notYetValid := webhookClaims(testLiveKitKey, []byte(body))
	notYetValid["nbf"] = testNow.Add(time.Hour).Unix()

	cases := map[string]string{
		"wrong secret":               signedWebhookToken(t, "another-secret", webhookClaims(testLiveKitKey, []byte(body))),
		"missing header":             "",
		"wrong issuer":               signedWebhookToken(t, testLiveKitSecret, webhookClaims("APIsomeoneElse", []byte(body))),
		"body changed after signing": signedWebhookToken(t, testLiveKitSecret, webhookClaims(testLiveKitKey, []byte(otherBody))),
		"expired":                    signedWebhookToken(t, testLiveKitSecret, expired),
		"not yet valid":              signedWebhookToken(t, testLiveKitSecret, notYetValid),
		"alg none":                   unsignedNoneToken(t, webhookClaims(testLiveKitKey, []byte(body))),
	}
	for name, token := range cases {
		t.Run(name, func(t *testing.T) {
			// Given LiveKit is configured
			setLiveKitEnv(t, testLiveKitURL, testLiveKitKey, testLiveKitSecret)
			db := newFakeDynamo()
			app := newVoiceTestApp(db)

			// When the body is posted with the bad token
			resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, webhookRequest(body, token))))

			// Then the request is rejected and nothing is written
			if resp.StatusCode != 401 {
				t.Fatalf("bad signature should be 401, got %d", resp.StatusCode)
			}
			if n := mutations(db); n != 0 {
				t.Fatalf("rejected webhook must not write, got %d writes", n)
			}
		})
	}
}

func TestLiveKitWebhookRejectsEverythingWithoutCredentials(t *testing.T) {
	body := roomEventBody(t, "participant_joined", "ROOM01", "player-1", testNow.Unix())
	cases := map[string]struct {
		envKey, envSecret, signWith, issuer string
	}{
		"empty secret, token signed with empty secret":  {testLiveKitKey, "", "", testLiveKitKey},
		"empty secret, token signed with a real secret": {testLiveKitKey, "", testLiveKitSecret, testLiveKitKey},
		"empty api key, token signed with real secret":  {"", testLiveKitSecret, testLiveKitSecret, ""},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			// Given LiveKit credentials that are missing a value
			setLiveKitEnv(t, testLiveKitURL, tc.envKey, tc.envSecret)
			db := newFakeDynamo()
			app := newVoiceTestApp(db)
			token := signedWebhookToken(t, tc.signWith, webhookClaims(tc.issuer, []byte(body)))

			// When a request arrives, even one whose signature checks out with the secret it carries
			resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, webhookRequest(body, token))))

			// Then every request is rejected
			if resp.StatusCode != 401 {
				t.Fatalf("missing LiveKit credentials must reject every request, got %d", resp.StatusCode)
			}
			if n := mutations(db); n != 0 {
				t.Fatalf("rejected webhook must not write, got %d writes", n)
			}
		})
	}
}
