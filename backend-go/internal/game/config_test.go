package game

import (
	"errors"
	"testing"

	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

func TestDefaultConfig_hasStandardCounts(t *testing.T) {
	cfg := DefaultConfig()

	if cfg.FaceDownCount != 3 || cfg.FaceUpCount != 3 || cfg.HandCount != 3 || cfg.BurnCount != 4 {
		t.Fatalf("unexpected default counts: %+v", cfg)
	}
}

func TestDefaultConfig_mapsSpecialRanks(t *testing.T) {
	cfg := DefaultConfig()

	want := map[int]rules.CardRule{2: rules.RuleJoker, 6: rules.RuleSmaller, 8: rules.RuleTransparent, 9: rules.RuleReverse, 10: rules.RuleBurner}
	for value, rule := range want {
		if got := cfg.RuleFor(value); got != rule {
			t.Fatalf("value %d: got %s, want %s", value, got, rule)
		}
	}
	for _, value := range []int{3, 5, 7, 11, 14} {
		if cfg.RuleFor(value) != rules.RuleDefault {
			t.Fatalf("value %d must default to DEFAULT", value)
		}
	}
}

func TestDefaultConfig_alwaysPlayableAndPlayAgain(t *testing.T) {
	cfg := DefaultConfig()

	if !cfg.IsAlwaysPlayable(2) || !cfg.IsAlwaysPlayable(8) || cfg.IsAlwaysPlayable(7) {
		t.Fatal("joker and transparent ranks must be always playable")
	}
	if !cfg.CanPlayAgain(10) || cfg.CanPlayAgain(2) || cfg.CanPlayAgain(9) {
		t.Fatal("only the burner rank grants a replay")
	}
}

func TestParseConfig_nil_returnsDefaults(t *testing.T) {
	cfg, err := ParseConfig(nil)

	if err != nil || cfg.DecksCount != 1 || cfg.BurnCount != 4 {
		t.Fatalf("nil config should yield defaults, got %+v, %v", cfg, err)
	}
}

func TestParseConfig_twoDecks_derivesBurnCountSix(t *testing.T) {
	cfg, err := ParseConfig(map[string]any{"decksCount": 2.0})

	if err != nil || cfg.DecksCount != 2 || cfg.BurnCount != 6 {
		t.Fatalf("two decks should burn at six, got %+v, %v", cfg, err)
	}
}

func TestParseConfig_invalidDeckCount_rejected(t *testing.T) {
	for _, bad := range []any{3.0, 0.0, 1.5, "2"} {
		if _, err := ParseConfig(map[string]any{"decksCount": bad}); !errors.Is(err, ErrInvalidConfig) {
			t.Fatalf("decksCount %v must be rejected, got %v", bad, err)
		}
	}
}

func TestParseConfig_zoneCounts_mustBeIntegersInRange(t *testing.T) {
	cases := []map[string]any{
		{"faceDownCount": -1.0},
		{"faceUpCount": 11.0},
		{"handCount": 2.5},
		{"handCount": "3"},
	}
	for _, c := range cases {
		if _, err := ParseConfig(c); !errors.Is(err, ErrInvalidConfig) {
			t.Fatalf("%v must be rejected, got %v", c, err)
		}
	}
}

func TestParseConfig_infeasibleZoneSizes_rejected(t *testing.T) {
	// 10+10+10 = 30 per seat; two seats need 60 cards, one deck has 52.
	_, err := ParseConfig(map[string]any{"faceDownCount": 10.0, "faceUpCount": 10.0, "handCount": 10.0})

	if !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("expected infeasible config to be rejected, got %v", err)
	}
}

func TestParseConfig_cardRules_derivesAlwaysPlayableAndPlayAgain(t *testing.T) {
	cfg, err := ParseConfig(map[string]any{
		"cardRules": map[string]any{"5": "JOKER", "7": "BURNER", "12": "TRANSPARENT"},
	})
	if err != nil {
		t.Fatal(err)
	}

	if cfg.RuleFor(5) != rules.RuleJoker || cfg.RuleFor(7) != rules.RuleBurner {
		t.Fatal("requested rules must apply")
	}
	if !cfg.IsAlwaysPlayable(5) || !cfg.IsAlwaysPlayable(12) || cfg.IsAlwaysPlayable(7) {
		t.Fatal("joker and transparent ranks must be always playable")
	}
	if !cfg.CanPlayAgain(7) || cfg.CanPlayAgain(5) {
		t.Fatal("only burner ranks grant a replay")
	}
	if cfg.RuleFor(2) != rules.RuleDefault {
		t.Fatal("ranks not listed in cardRules fall back to DEFAULT")
	}
}

func TestParseConfig_unknownRule_rejected(t *testing.T) {
	_, err := ParseConfig(map[string]any{"cardRules": map[string]any{"2": "TELEPORT"}})

	if !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("unknown rule must be rejected, got %v", err)
	}
}

func TestParseConfig_unknownKeys_areIgnored(t *testing.T) {
	cfg, err := ParseConfig(map[string]any{"admin": true, "burnCount": 99.0, "decksCount": 1.0})

	if err != nil || cfg.BurnCount != 4 {
		t.Fatalf("unknown keys must be ignored and burnCount derived, got %+v, %v", cfg, err)
	}
}

func TestParseConfig_mixedFlag_mustBeBoolean(t *testing.T) {
	if _, err := ParseConfig(map[string]any{"allowMixedHandAndFaceUpWhenDeckEmpty": "yes"}); !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("non-boolean flag must be rejected, got %v", err)
	}
	cfg, err := ParseConfig(map[string]any{"allowMixedHandAndFaceUpWhenDeckEmpty": true})
	if err != nil || !cfg.AllowMixedHandAndFaceUpWhenDeckEmpty {
		t.Fatalf("boolean flag must be honoured, got %+v, %v", cfg, err)
	}
}

func TestParseConfig_notAnObject_rejected(t *testing.T) {
	if _, err := ParseConfig("decks=1"); !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("a non-object config must be rejected, got %v", err)
	}
}
