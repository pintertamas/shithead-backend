package main

import (
	"strings"
	"testing"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

func restCreateGameWithGroups(sub string, groups any, body string) map[string]any {
	req := restCreateGame(sub, body)
	claims := req["requestContext"].(map[string]any)["authorizer"].(map[string]any)["claims"].(map[string]any)
	claims["cognito:groups"] = groups
	return req
}

func TestCreateGameRejectsVoiceForNonAdminWithoutWriting(t *testing.T) {
	for name, groups := range map[string]any{
		"no groups":    nil,
		"other group":  []any{"players"},
		"string other": "[players]",
		"admin prefix": "[game-admins]",
		"not a list":   42.0,
	} {
		t.Run(name, func(t *testing.T) {
			db := newFakeDynamo()
			app := newTestApp(db, "VOICE1")
			req := restCreateGameWithGroups("owner", groups, `{"config":{"voiceEnabled":true}}`)
			resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, req)))
			if resp.StatusCode != 403 {
				t.Fatalf("non-admin voice should be 403, got %d: %s", resp.StatusCode, resp.Body)
			}
			if !strings.Contains(resp.Body, `"message":"Only administrators can enable voice chat."`) {
				t.Fatalf("unexpected body %s", resp.Body)
			}
			if n := db.writes("PutItem"); n != 0 {
				t.Fatalf("rejected voice game must not write, got %d puts", n)
			}
		})
	}
}

func TestCreateGameStoresVoiceForGameAdmin(t *testing.T) {
	for name, groups := range map[string]any{
		"array":        []any{"players", "game-admin"},
		"string":       "[game-admin]",
		"string multi": "[players game-admin]",
	} {
		t.Run(name, func(t *testing.T) {
			db := newFakeDynamo()
			app := newTestApp(db, "VOICE2")
			req := restCreateGameWithGroups("owner", groups, `{"config":{"voiceEnabled":true}}`)
			resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, req)))
			if resp.StatusCode != 200 {
				t.Fatalf("admin voice should be 200, got %d: %s", resp.StatusCode, resp.Body)
			}
			config := db.item(testGames, "VOICE2")["config"].(*types.AttributeValueMemberM).Value
			if v, ok := config["voiceEnabled"].(*types.AttributeValueMemberBOOL); !ok || !v.Value {
				t.Fatalf("voiceEnabled should be stored as true BOOL, got %v", config["voiceEnabled"])
			}
		})
	}
}

func TestCreateGameVoiceDefaultsToFalseForNonAdmin(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db, "VOICE3")
	resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, restCreateGameWithGroups("owner", nil, `{"config":{}}`))))
	if resp.StatusCode != 200 {
		t.Fatalf("create-game without voice should be 200, got %d", resp.StatusCode)
	}
	config := db.item(testGames, "VOICE3")["config"].(*types.AttributeValueMemberM).Value
	if v, ok := config["voiceEnabled"].(*types.AttributeValueMemberBOOL); !ok || v.Value {
		t.Fatalf("voiceEnabled should be stored as false BOOL, got %v", config["voiceEnabled"])
	}
}

func TestCreateGameRejectsNonBooleanVoice(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db, "VOICE4")
	req := restCreateGameWithGroups("admin", []any{"game-admin"}, `{"config":{"voiceEnabled":"yes"}}`)
	resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, req)))
	if resp.StatusCode != 400 || db.writes("PutItem") != 0 {
		t.Fatalf("non-boolean voiceEnabled should be 400 without writing, got %d", resp.StatusCode)
	}
}

func TestCreateGameRefusesVoiceAtGuardThreshold(t *testing.T) {
	// Given 4500 participant-minutes already used this month
	db := newFakeDynamo()
	seedVoiceUsage(db, testNow, 4500)
	app := newVoiceTestApp(db)

	// When an administrator creates a game with voice enabled
	req := restCreateGameWithGroups("admin", []any{"game-admin"}, `{"config":{"voiceEnabled":true}}`)
	resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, req)))

	// Then the create is refused with the pause message and nothing is written
	if resp.StatusCode != 409 {
		t.Fatalf("voice at the guard should be 409, got %d: %s", resp.StatusCode, resp.Body)
	}
	if want := `{"message":"Voice chat is paused until next month to stay within the free LiveKit allowance."}`; resp.Body != want {
		t.Fatalf("unexpected body %s", resp.Body)
	}
	if n := db.writes("PutItem"); n != 0 {
		t.Fatalf("refused game must not write, got %d puts", n)
	}
}

func TestCreateGameAllowsVoiceJustBelowGuard(t *testing.T) {
	// Given 4499 participant-minutes used this month
	db := newFakeDynamo()
	seedVoiceUsage(db, testNow, 4499)
	app := newVoiceTestApp(db)

	// When an administrator creates a game with voice enabled
	req := restCreateGameWithGroups("admin", []any{"game-admin"}, `{"config":{"voiceEnabled":true}}`)
	resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, req)))

	// Then the game is created with voice on
	if resp.StatusCode != 200 {
		t.Fatalf("voice below the guard should be 200, got %d: %s", resp.StatusCode, resp.Body)
	}
	config := db.item(testGames, "ZZZZZZ")["config"].(*types.AttributeValueMemberM).Value
	if v, ok := config["voiceEnabled"].(*types.AttributeValueMemberBOOL); !ok || !v.Value {
		t.Fatalf("voiceEnabled should be stored as true, got %v", config["voiceEnabled"])
	}
}

func TestCreateGameIgnoresVoiceUsageWhenVoiceIsOff(t *testing.T) {
	// Given the month is already past the allowance
	db := newFakeDynamo()
	seedVoiceUsage(db, testNow, 5000)
	app := newVoiceTestApp(db)

	// When a player creates a game without voice
	req := restCreateGameWithGroups("owner", nil, `{"config":{}}`)
	resp := proxyResponse(t, mustHandle(t, app, mustRaw(t, req)))

	// Then the create is unaffected by the guard
	if resp.StatusCode != 200 {
		t.Fatalf("game without voice should be 200 at any usage, got %d: %s", resp.StatusCode, resp.Body)
	}
}
