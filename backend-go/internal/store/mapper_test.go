package store

import (
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"github.com/pintertamas/shithead-backend/backend-go/internal/game"
	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

func card(suit rules.Suit, value int) rules.Card {
	return rules.Card{Suit: suit, Value: value, Rule: rules.RuleDefault}
}

func TestMapper_roundTrip_preservesGameState(t *testing.T) {
	// Given a started two-player game with cards in every zone
	s := game.NewSession("rt", "p1", game.DefaultConfig())
	_ = s.AddPlayer("p1", "alice")
	_ = s.AddPlayer("p2", "bob")
	s.Started = true
	s.SetupComplete = false
	s.CurrentIndex = 1
	s.Players[0].Hand = []rules.Card{card(rules.SuitHearts, 7)}
	s.Players[0].FaceUp = []rules.Card{card(rules.SuitClubs, 5)}
	s.Players[1].Out = true
	s.Players[1].Ready = false
	s.Discard = []rules.Card{card(rules.SuitHearts, 9)}
	s.Deck = game.NewDeck([]rules.Card{card(rules.SuitSpades, 3)})
	s.ShitheadID = "p2"

	// When
	record := ToRecord(s, GameRecord{CreatedAt: "2026-01-01T00:00:00.000000+00:00", TTL: 42})
	item, err := attributevalue.MarshalMap(record)
	if err != nil {
		t.Fatal(err)
	}
	var decoded GameRecord
	if err := attributevalue.UnmarshalMap(item, &decoded); err != nil {
		t.Fatal(err)
	}
	restored := FromRecord(decoded)

	// Then
	if restored.ID != "rt" || restored.OwnerID != "p1" || !restored.Started || restored.SetupComplete {
		t.Fatalf("metadata lost: %+v", restored)
	}
	if restored.CurrentPlayerID() != "p2" {
		t.Fatalf("current player lost, got %s", restored.CurrentPlayerID())
	}
	if restored.Players[0].Username != "alice" || restored.Players[0].Hand[0].Value != 7 {
		t.Fatal("player state lost")
	}
	if !restored.Players[1].Out || restored.Players[1].Ready {
		t.Fatal("out and ready flags lost")
	}
	if restored.Discard[0].Value != 9 || restored.Deck.Len() != 1 {
		t.Fatal("pile or deck lost")
	}
	if restored.ShitheadID != "p2" {
		t.Fatal("shithead lost")
	}
	if decoded.CreatedAt != "2026-01-01T00:00:00.000000+00:00" || decoded.TTL != 42 {
		t.Fatal("created_at and ttl must be preserved by ToRecord")
	}
}

func TestToRecord_preservesStartingAndEloFlags(t *testing.T) {
	// Given a stored record with the starting and eloUpdated flags set
	prev := GameRecord{GameID: "g", Starting: true, EloUpdated: true}
	s := game.NewSession("g", "p1", game.DefaultConfig())
	_ = s.AddPlayer("p1", "alice")

	// When a session is saved
	record := ToRecord(s, prev)

	// Then both flags survive the save
	if !record.Starting || !record.EloUpdated {
		t.Fatal("every save must preserve starting and eloUpdated")
	}
}

func TestDecodeGame_legacyItemWithoutSetupComplete_defaultsToTrue(t *testing.T) {
	// Given an item written by the Python create_game (no setupComplete, no ready)
	item := map[string]types.AttributeValue{
		"game_id":         &types.AttributeValueMemberS{Value: "ABC123"},
		"user_id":         &types.AttributeValueMemberS{Value: "owner"},
		"currentPlayerId": &types.AttributeValueMemberS{Value: "owner"},
		"started":         &types.AttributeValueMemberBOOL{Value: false},
		"finished":        &types.AttributeValueMemberBOOL{Value: false},
		"shitheadId":      &types.AttributeValueMemberNULL{Value: true},
		"discardPile":     &types.AttributeValueMemberL{Value: []types.AttributeValue{}},
		"deck":            &types.AttributeValueMemberL{Value: []types.AttributeValue{}},
		"players": &types.AttributeValueMemberL{Value: []types.AttributeValue{
			&types.AttributeValueMemberM{Value: map[string]types.AttributeValue{
				"playerId": &types.AttributeValueMemberS{Value: "owner"},
				"username": &types.AttributeValueMemberS{Value: "alice"},
				"hand":     &types.AttributeValueMemberL{Value: []types.AttributeValue{}},
				"faceUp":   &types.AttributeValueMemberL{Value: []types.AttributeValue{}},
				"faceDown": &types.AttributeValueMemberL{Value: []types.AttributeValue{}},
				"out":      &types.AttributeValueMemberBOOL{Value: false},
			}},
		}},
	}

	// When
	record, err := decodeGame(item)
	if err != nil {
		t.Fatal(err)
	}

	// Then the legacy defaults apply
	if !record.SetupComplete {
		t.Fatal("missing setupComplete must default to true")
	}
	if record.Players[0].Ready == nil || !*record.Players[0].Ready {
		t.Fatal("missing ready must default to true")
	}
	if record.Config != nil {
		t.Fatal("missing config must stay nil so the default config applies")
	}
	session := FromRecord(record)
	if session.Config.DecksCount != 1 || session.Config.BurnCount != 4 {
		t.Fatal("missing config must fall back to the default rules")
	}
}

func TestNewGameRecord_matchesPythonLobbyLayout(t *testing.T) {
	now := time.Date(2026, 10, 9, 19, 16, 0, 123456000, time.UTC)

	record := NewGameRecord("ABC123", "owner", "alice", game.DefaultConfig(), now)

	if record.CurrentPlayerID != "owner" || record.Started || !record.SetupComplete {
		t.Fatalf("unexpected lobby state: %+v", record)
	}
	if record.CreatedAt != "2026-10-09T19:16:00.123456+00:00" {
		t.Fatalf("created_at format changed: %s", record.CreatedAt)
	}
	if record.TTL != now.Unix()+3600 {
		t.Fatal("ttl must be one hour ahead")
	}
	if record.Config == nil || record.Config.CardRules["10"] != "BURNER" {
		t.Fatal("config must be stored with the card rules")
	}
}

func TestConfigRoundTrip_keepsAllowFailedFaceUpPlay(t *testing.T) {
	cfg := game.DefaultConfig()
	cfg.AllowFailedFaceUpPlay = true

	restored := configFromRecord(configToRecord(cfg))

	if !restored.AllowFailedFaceUpPlay || !restored.IsAlwaysPlayable(2) || !restored.CanPlayAgain(10) {
		t.Fatal("config fields must survive the store round trip")
	}
}

func TestNormalizeUsername_caseWhitespaceAndCompatibility(t *testing.T) {
	cases := map[string]string{
		"  Alice   Smith ": "alice smith",
		"ＡＬＩＣＥ":            "alice",
		"Bob":              "bob",
	}
	for in, want := range cases {
		if got := NormalizeUsername(in); got != want {
			t.Fatalf("NormalizeUsername(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestClaimID_usesExistingPrefix(t *testing.T) {
	if got := claimID("alice"); got != "__username__#alice" {
		t.Fatalf("claim id changed: %s", got)
	}
}
