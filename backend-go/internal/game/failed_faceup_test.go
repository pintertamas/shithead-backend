package game

import (
	"testing"

	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

// faceUpSetup returns a started two-player game where alice has an empty hand
// and one face-up card of the given value, with a 9 on the pile.
func faceUpSetup(t *testing.T, allowFailed bool, faceUpValue int) *Session {
	t.Helper()
	cfg := DefaultConfig()
	cfg.AllowFailedFaceUpPlay = allowFailed
	s := NewSession("failed-faceup", "p1", cfg)
	_ = s.AddPlayer("p1", "alice")
	_ = s.AddPlayer("p2", "bob")
	prepareStarted(s)
	alice := s.Players[0]
	alice.FaceUp = []rules.Card{cardOf(faceUpValue)}
	alice.FaceDown = []rules.Card{cardOf(12)}
	s.Discard = append(s.Discard, cardOf(9))
	return s
}

func TestFailedFaceUpPlay_optionOn_picksUpWholePile(t *testing.T) {
	// Given a 3 face-up card that cannot be played on a 9
	s := faceUpSetup(t, true, 3)
	alice := s.Players[0]

	// When
	result := s.PlayCards([]rules.Card{cardOf(3)})

	// Then the 3 and the pile go to alice's hand and the turn passes
	if result != ResultPickup {
		t.Fatalf("expected pickup, got %v", result)
	}
	if len(alice.FaceUp) != 0 || len(s.Discard) != 0 {
		t.Fatal("face-up zone and pile must be emptied")
	}
	if len(alice.Hand) != 2 || !contains(alice.Hand, cardOf(3)) || !contains(alice.Hand, cardOf(9)) {
		t.Fatalf("hand must hold the failed card and the pile, got %v", alice.Hand)
	}
	if s.CurrentPlayerID() != "p2" {
		t.Fatalf("turn must advance to p2, got %s", s.CurrentPlayerID())
	}
}

func TestFailedFaceUpPlay_optionOff_isRejectedAndUnchanged(t *testing.T) {
	s := faceUpSetup(t, false, 3)
	alice := s.Players[0]

	result := s.PlayCards([]rules.Card{cardOf(3)})

	if result != ResultInvalid {
		t.Fatalf("expected invalid, got %v", result)
	}
	if len(alice.FaceUp) != 1 || len(s.Discard) != 1 || len(alice.Hand) != 0 {
		t.Fatal("a rejected face-up play must leave every zone unchanged")
	}
	if s.CurrentPlayerID() != "p1" {
		t.Fatal("a rejected play must not advance the turn")
	}
}

func TestFailedFaceUpPlay_legalPlay_isUnaffectedByOption(t *testing.T) {
	s := faceUpSetup(t, true, 11)

	if r := s.PlayCards([]rules.Card{cardOf(11)}); r != ResultSuccess {
		t.Fatalf("a legal face-up play must succeed, got %v", r)
	}
}

func TestParseConfig_allowFailedFaceUpPlay(t *testing.T) {
	cfg, err := ParseConfig(map[string]any{"allowFailedFaceUpPlay": true})
	if err != nil || !cfg.AllowFailedFaceUpPlay {
		t.Fatalf("flag must be honoured, got %+v, %v", cfg, err)
	}
	cfg, err = ParseConfig(nil)
	if err != nil || cfg.AllowFailedFaceUpPlay {
		t.Fatal("flag must default to false")
	}
	if _, err := ParseConfig(map[string]any{"allowFailedFaceUpPlay": 1.0}); err == nil {
		t.Fatal("non-boolean flag must be rejected")
	}
}
