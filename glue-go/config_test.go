package main

import (
	"reflect"
	"testing"
)

func TestParseGameConfigDefaults(t *testing.T) {
	cfg, err := parseGameConfig(map[string]any{})
	if err != nil {
		t.Fatalf("empty body should be valid: %v", err)
	}
	want := GameConfig{
		DecksCount:     1,
		BurnCount:      4,
		FaceDownCount:  3,
		FaceUpCount:    3,
		HandCount:      3,
		CardRules:      map[string]string{"2": "JOKER", "6": "SMALLER", "8": "TRANSPARENT", "9": "REVERSE", "10": "BURNER"},
		AlwaysPlayable: []int{2, 8},
		CanPlayAgain:   []int{10},
	}
	if !reflect.DeepEqual(cfg, want) {
		t.Fatalf("defaults mismatch:\n got %+v\nwant %+v", cfg, want)
	}
}

func TestParseGameConfigDeckCountDerivesBurnCount(t *testing.T) {
	cfg, err := parseGameConfig(map[string]any{"config": map[string]any{"decksCount": 2.0}})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DecksCount != 2 || cfg.BurnCount != 6 {
		t.Fatalf("two decks should burn at 6, got decks=%d burn=%d", cfg.DecksCount, cfg.BurnCount)
	}
}

func TestParseGameConfigRejectsInvalidValues(t *testing.T) {
	tests := map[string]map[string]any{
		"decks zero":            {"decksCount": 0.0},
		"decks three":           {"decksCount": 3.0},
		"decks fractional":      {"decksCount": 1.5},
		"decks string":          {"decksCount": "1"},
		"face down too many":    {"faceDownCount": 11.0},
		"face up negative":      {"faceUpCount": -1.0},
		"hand fractional":       {"handCount": 2.5},
		"hand string":           {"handCount": "3"},
		"mixed not bool":        {"allowMixedHandAndFaceUpWhenDeckEmpty": "yes"},
		"failed play not bool":  {"allowFailedFaceUpPlay": 1.0},
		"card rules not object": {"cardRules": []any{"JOKER"}},
		"unknown rule":          {"cardRules": map[string]any{"5": "EXPLODE"}},
		"rule not string":       {"cardRules": map[string]any{"5": 1.0}},
	}
	for name, requested := range tests {
		t.Run(name, func(t *testing.T) {
			if _, err := parseGameConfig(map[string]any{"config": requested}); err == nil {
				t.Fatalf("expected %v to be rejected", requested)
			}
		})
	}
}

func TestParseGameConfigRejectsNonObjectConfig(t *testing.T) {
	for _, body := range []map[string]any{
		{"config": "default"},
		{"config": []any{}},
	} {
		if _, err := parseGameConfig(body); err == nil {
			t.Fatalf("expected %v to be rejected", body)
		}
	}
}

func TestParseGameConfigAcceptsBoundsAndIgnoresUnknownKeys(t *testing.T) {
	cfg, err := parseGameConfig(map[string]any{"config": map[string]any{
		"faceDownCount":                        10.0,
		"faceUpCount":                          0.0,
		"handCount":                            10.0,
		"allowMixedHandAndFaceUpWhenDeckEmpty": true,
		"allowFailedFaceUpPlay":                true,
		"somethingElse":                        "ignored",
	}})
	if err != nil {
		t.Fatalf("bounds should be accepted: %v", err)
	}
	if cfg.FaceDownCount != 10 || cfg.FaceUpCount != 0 || cfg.HandCount != 10 {
		t.Fatalf("counts not applied: %+v", cfg)
	}
	if !cfg.AllowMixedHandAndFaceUpWhenDeckEmpty || !cfg.AllowFailedFaceUpPlay {
		t.Fatalf("boolean options not applied: %+v", cfg)
	}
}

func TestParseGameConfigBooleanOptionsDefaultToFalse(t *testing.T) {
	cfg, err := parseGameConfig(map[string]any{"config": map[string]any{}})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AllowMixedHandAndFaceUpWhenDeckEmpty || cfg.AllowFailedFaceUpPlay {
		t.Fatalf("boolean options should default to false: %+v", cfg)
	}
}

func TestParseGameConfigCardRulesDeriveAlwaysPlayableAndCanPlayAgain(t *testing.T) {
	cfg, err := parseGameConfig(map[string]any{"config": map[string]any{
		"cardRules": map[string]any{"5": "JOKER", "7": "TRANSPARENT", "10": "BURNER", "12": "REVERSE", "3": "SMALLER"},
	}})
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.CardRules) != 13 {
		t.Fatalf("expected a rule for every rank 2..14, got %d", len(cfg.CardRules))
	}
	if cfg.CardRules["2"] != "DEFAULT" || cfg.CardRules["12"] != "REVERSE" {
		t.Fatalf("unexpected rules: %v", cfg.CardRules)
	}
	if want := []int{5, 7}; !reflect.DeepEqual(cfg.AlwaysPlayable, want) {
		t.Fatalf("alwaysPlayable = %v, want %v", cfg.AlwaysPlayable, want)
	}
	if want := []int{10}; !reflect.DeepEqual(cfg.CanPlayAgain, want) {
		t.Fatalf("canPlayAgain = %v, want %v", cfg.CanPlayAgain, want)
	}
}

func TestParseGameConfigEmptyRuleListsAreNotNil(t *testing.T) {
	cfg, err := parseGameConfig(map[string]any{"config": map[string]any{"cardRules": map[string]any{}}})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AlwaysPlayable == nil || cfg.CanPlayAgain == nil {
		t.Fatal("empty rule lists must be non-nil so they marshal as DynamoDB lists")
	}
}
