package main

import (
	"strconv"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

// seedVoiceUsage stores a month's total the way the counter writes it.
func seedVoiceUsage(db *fakeDynamo, at time.Time, minutes int64) {
	db.seed(testUsers, map[string]types.AttributeValue{
		"user_id": s(voiceUsageKey(at)),
		"minutes": &types.AttributeValueMemberN{Value: strconv.FormatInt(minutes, 10)},
	})
}

// usageMinutes reads the stored total of the UTC month of at (0 when absent).
func usageMinutes(db *fakeDynamo, at time.Time) int64 {
	minutes, _ := numberAttr(db.item(testUsers, voiceUsageKey(at)), "minutes")
	return minutes
}

func TestVoiceJoinThenLeaveAddsRoundedMinutes(t *testing.T) {
	// Given a participant who joined at testNow
	setLiveKitEnv(t, testLiveKitURL, testLiveKitKey, testLiveKitSecret)
	db := newFakeDynamo()
	app := newVoiceTestApp(db)
	joined := testNow.Unix()
	deliverWebhook(t, app, roomEventBody(t, "participant_joined", "ROOM01", "player-1", joined))

	// When they leave 150 seconds later
	resp := deliverWebhook(t, app, roomEventBody(t, "participant_left", "ROOM01", "player-1", joined+150))

	// Then 150 seconds is billed as 3 minutes and the open session no longer has joined_at
	if resp.StatusCode != 200 {
		t.Fatalf("leave should be 200, got %d", resp.StatusCode)
	}
	if got := usageMinutes(db, testNow); got != 3 {
		t.Fatalf("150 seconds should count as 3 minutes, got %d", got)
	}
	if _, open := numberAttr(db.item(testUsers, voiceOpenKey("ROOM01", "player-1")), "joined_at"); open {
		t.Fatal("leave should remove joined_at from the open session row")
	}
}

func TestVoiceDuplicateLeaveAddsNothing(t *testing.T) {
	// Given a participant who joined and left once (3 minutes counted)
	setLiveKitEnv(t, testLiveKitURL, testLiveKitKey, testLiveKitSecret)
	db := newFakeDynamo()
	app := newVoiceTestApp(db)
	joined := testNow.Unix()
	deliverWebhook(t, app, roomEventBody(t, "participant_joined", "ROOM01", "player-1", joined))
	leave := roomEventBody(t, "participant_left", "ROOM01", "player-1", joined+150)
	deliverWebhook(t, app, leave)

	// When LiveKit delivers the same leave event again
	resp := deliverWebhook(t, app, leave)

	// Then the total does not change and no second update is made
	if resp.StatusCode != 200 {
		t.Fatalf("duplicate leave should still be 200, got %d", resp.StatusCode)
	}
	// The total stays at 3: a second ADD would have made it 6
	if got := usageMinutes(db, testNow); got != 3 {
		t.Fatalf("duplicate leave must not double count, got %d minutes", got)
	}
}

func TestVoiceLeaveWithoutJoinAddsNothing(t *testing.T) {
	// Given no open session for the participant
	setLiveKitEnv(t, testLiveKitURL, testLiveKitKey, testLiveKitSecret)
	db := newFakeDynamo()
	app := newVoiceTestApp(db)

	// When a leave event arrives for them
	resp := deliverWebhook(t, app, roomEventBody(t, "participant_left", "ROOM01", "player-1", testNow.Unix()+120))

	// Then nothing is counted
	if resp.StatusCode != 200 {
		t.Fatalf("unmatched leave should still be 200, got %d", resp.StatusCode)
	}
	// No usage item is created: the condition failed before any ADD ran
	if db.item(testUsers, voiceUsageKey(testNow)) != nil {
		t.Fatal("leave without join must not create this month's usage item")
	}
	if got := usageMinutes(db, testNow); got != 0 {
		t.Fatalf("leave without join must add nothing, got %d minutes", got)
	}
}

func TestVoiceRejoinReplacesOldSessionSoMissedLeaveIsNotCounted(t *testing.T) {
	// Given a join whose leave was never delivered, followed by a new join 1000 seconds later
	setLiveKitEnv(t, testLiveKitURL, testLiveKitKey, testLiveKitSecret)
	db := newFakeDynamo()
	app := newVoiceTestApp(db)
	first := testNow.Unix()
	deliverWebhook(t, app, roomEventBody(t, "participant_joined", "ROOM01", "player-1", first))
	second := first + 1000
	deliverWebhook(t, app, roomEventBody(t, "participant_joined", "ROOM01", "player-1", second))

	// When the second connection leaves after 60 seconds
	deliverWebhook(t, app, roomEventBody(t, "participant_left", "ROOM01", "player-1", second+60))

	// Then only the second connection is counted (1 minute, not the 18 a kept first join would give)
	if got := usageMinutes(db, testNow); got != 1 {
		t.Fatalf("only the latest connection should count, got %d minutes", got)
	}
}

func TestVoiceMinutesLandInMonthOfTheLeave(t *testing.T) {
	// Given a join on the last minute of October (UTC)
	setLiveKitEnv(t, testLiveKitURL, testLiveKitKey, testLiveKitSecret)
	db := newFakeDynamo()
	app := newVoiceTestApp(db)
	october := time.Date(2026, 10, 31, 23, 59, 0, 0, time.UTC)
	november := time.Date(2026, 11, 1, 0, 1, 0, 0, time.UTC)
	deliverWebhook(t, app, roomEventBody(t, "participant_joined", "ROOM01", "player-1", october.Unix()))

	// When the participant leaves in the first minute of November (UTC)
	deliverWebhook(t, app, roomEventBody(t, "participant_left", "ROOM01", "player-1", november.Unix()))

	// Then the two minutes are recorded for November and October stays empty
	if got := usageMinutes(db, november); got != 2 {
		t.Fatalf("minutes should land in the month of the leave, got %d", got)
	}
	if got := usageMinutes(db, october); got != 0 {
		t.Fatalf("October should be untouched, got %d", got)
	}
}

func TestVoiceEventsAcceptQuotedCreatedAt(t *testing.T) {
	// Given createdAt sent as a quoted integer, as protobuf JSON writes int64
	setLiveKitEnv(t, testLiveKitURL, testLiveKitKey, testLiveKitSecret)
	db := newFakeDynamo()
	app := newVoiceTestApp(db)
	joined := strconv.FormatInt(testNow.Unix(), 10)
	left := strconv.FormatInt(testNow.Unix()+150, 10)
	deliverWebhook(t, app, roomEventBody(t, "participant_joined", "ROOM01", "player-1", joined))

	// When the leave arrives with its createdAt quoted too
	resp := deliverWebhook(t, app, roomEventBody(t, "participant_left", "ROOM01", "player-1", left))

	// Then the event is read and counted like the numeric form
	if resp.StatusCode != 200 {
		t.Fatalf("quoted createdAt should be accepted, got %d: %s", resp.StatusCode, resp.Body)
	}
	if got := usageMinutes(db, testNow); got != 3 {
		t.Fatalf("quoted createdAt should count 3 minutes, got %d", got)
	}
}

func TestConnectedMinutesRoundsUpAndNeverBelowOne(t *testing.T) {
	const start int64 = 1_000_000
	cases := map[string]struct {
		left int64
		want int64
	}{
		"same second":        {start, 1},
		"one second":         {start + 1, 1},
		"exactly one minute": {start + 60, 1},
		"61 seconds":         {start + 61, 2},
		"an hour":            {start + 3600, 60},
		"clock went back":    {start - 30, 1},
	}
	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			// Given a connection that opened at start
			// When it is closed at tc.left
			got := connectedMinutes(start, tc.left)
			// Then it is billed in whole minutes, at least one
			if got != tc.want {
				t.Fatalf("connectedMinutes = %d, want %d", got, tc.want)
			}
		})
	}
}
