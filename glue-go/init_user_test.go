package main

import (
	"context"
	"testing"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

const testEmail = "user@example.test"

// cognitoEvent builds a post-authentication trigger event with the given user attributes.
func cognitoEvent(attrs map[string]string) map[string]any {
	return map[string]any{
		"triggerSource": "PostAuthentication_Authentication",
		"request":       map[string]any{"userAttributes": attrs},
	}
}

func TestInitUserStoresEmailFromTokenClaim(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db)

	mustHandle(t, app, mustRaw(t, cognitoEvent(map[string]string{"sub": "user-1", "email": testEmail})))

	if stringAttr(db.item(testUsers, "user-1"), "email") != testEmail {
		t.Fatal("email from the token claim was not stored on the profile row")
	}
}

func TestInitUserReplacesStoredEmailWhenClaimChanges(t *testing.T) {
	db := newFakeDynamo()
	db.seed(testUsers, map[string]types.AttributeValue{"user_id": s("user-2"), "email": s("old@example.test")})
	app := newTestApp(db)

	mustHandle(t, app, mustRaw(t, cognitoEvent(map[string]string{"sub": "user-2", "email": testEmail})))

	if stringAttr(db.item(testUsers, "user-2"), "email") != testEmail {
		t.Fatal("a changed email claim must replace the stored email")
	}
}

func TestInitUserKeepsStoredEmailWhenClaimIsMissing(t *testing.T) {
	db := newFakeDynamo()
	db.seed(testUsers, map[string]types.AttributeValue{"user_id": s("user-3"), "email": s(testEmail)})
	app := newTestApp(db)

	mustHandle(t, app, mustRaw(t, cognitoEvent(map[string]string{"sub": "user-3"})))

	if stringAttr(db.item(testUsers, "user-3"), "email") != testEmail {
		t.Fatal("a token without an email claim must keep the stored email")
	}
}

func TestInitUserLeavesEmailUnsetWhenClaimIsMissingForNewUser(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db)

	mustHandle(t, app, mustRaw(t, cognitoEvent(map[string]string{"sub": "user-4"})))

	profile := db.item(testUsers, "user-4")
	if profile == nil {
		t.Fatal("profile was not seeded")
	}
	if _, ok := profile["email"]; ok {
		t.Fatal("no email attribute may be written when the token has no email claim")
	}
}

func TestSeedUserWritesOnlyTheSeedAttributesWhenEmailIsEmpty(t *testing.T) {
	db := newFakeDynamo()
	app := newTestApp(db)

	if err := app.seedUser(context.Background(), "user-5", "Ann", ""); err != nil {
		t.Fatal(err)
	}

	profile := db.item(testUsers, "user-5")
	if stringAttr(profile, "username") != "Ann" || stringAttr(profile, "leaderboard_pk") != "global" {
		t.Fatalf("seed attributes not written: %v", profile)
	}
	if _, ok := profile["email"]; ok {
		t.Fatal("an empty email must not be written")
	}
}
