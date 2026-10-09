package main

import (
	"errors"
	"fmt"
	"math"
	"strconv"
)

// GameConfig is the per-game configuration stored in the game session item.
// Field names match the Java GameConfigEntity attributes.
type GameConfig struct {
	DecksCount                           int               `dynamodbav:"decksCount"`
	BurnCount                            int               `dynamodbav:"burnCount"`
	FaceDownCount                        int               `dynamodbav:"faceDownCount"`
	FaceUpCount                          int               `dynamodbav:"faceUpCount"`
	HandCount                            int               `dynamodbav:"handCount"`
	AllowMixedHandAndFaceUpWhenDeckEmpty bool              `dynamodbav:"allowMixedHandAndFaceUpWhenDeckEmpty"`
	AllowFailedFaceUpPlay                bool              `dynamodbav:"allowFailedFaceUpPlay"`
	CardRules                            map[string]string `dynamodbav:"cardRules"`
	AlwaysPlayable                       []int             `dynamodbav:"alwaysPlayable"`
	CanPlayAgain                         []int             `dynamodbav:"canPlayAgain"`
	VoiceEnabled                         bool              `dynamodbav:"voiceEnabled"`
}

const (
	minFaceCards = 0
	maxFaceCards = 10
	firstRank    = 2
	lastRank     = 14
)

var (
	errConfigNotObject  = errors.New("config must be an object")
	errDecksCount       = errors.New("decksCount must be 1 or 2")
	errCountOutOfRange  = errors.New("card counts must be integers from 0 to 10")
	errBoolField        = errors.New("boolean option must be true or false")
	errCardRulesObject  = errors.New("cardRules must be an object")
	errCardRuleValue    = errors.New("cardRules contains an unsupported rule")
	validCardRuleValues = map[string]bool{
		"DEFAULT": true, "JOKER": true, "SMALLER": true,
		"TRANSPARENT": true, "REVERSE": true, "BURNER": true,
	}
)

// defaultCardRules is what a game gets when the client sends no cardRules.
func defaultCardRules() map[string]string {
	return map[string]string{"2": "JOKER", "6": "SMALLER", "8": "TRANSPARENT", "9": "REVERSE", "10": "BURNER"}
}

// parseGameConfig validates the "config" member of a create-game body.
// Missing members take their defaults, unknown members are ignored, and any
// present member with the wrong type or an out-of-range value is rejected.
func parseGameConfig(body map[string]any) (GameConfig, error) {
	raw, present := body["config"]
	if !present || raw == nil {
		raw = map[string]any{}
	}
	requested, ok := raw.(map[string]any)
	if !ok {
		return GameConfig{}, errConfigNotObject
	}
	return buildGameConfig(requested)
}

func buildGameConfig(requested map[string]any) (GameConfig, error) {
	decks, err := intField(requested, "decksCount", 1, 1, 2, errDecksCount)
	if err != nil {
		return GameConfig{}, err
	}
	faceDown, err := intField(requested, "faceDownCount", 3, minFaceCards, maxFaceCards, errCountOutOfRange)
	if err != nil {
		return GameConfig{}, err
	}
	faceUp, err := intField(requested, "faceUpCount", 3, minFaceCards, maxFaceCards, errCountOutOfRange)
	if err != nil {
		return GameConfig{}, err
	}
	hand, err := intField(requested, "handCount", 3, minFaceCards, maxFaceCards, errCountOutOfRange)
	if err != nil {
		return GameConfig{}, err
	}
	mixed, err := boolField(requested, "allowMixedHandAndFaceUpWhenDeckEmpty")
	if err != nil {
		return GameConfig{}, err
	}
	failed, err := boolField(requested, "allowFailedFaceUpPlay")
	if err != nil {
		return GameConfig{}, err
	}
	voice, err := boolField(requested, "voiceEnabled")
	if err != nil {
		return GameConfig{}, err
	}

	cfg := GameConfig{
		DecksCount:                           decks,
		BurnCount:                            burnCountFor(decks),
		FaceDownCount:                        faceDown,
		FaceUpCount:                          faceUp,
		HandCount:                            hand,
		AllowMixedHandAndFaceUpWhenDeckEmpty: mixed,
		AllowFailedFaceUpPlay:                failed,
		VoiceEnabled:                         voice,
		CardRules:                            defaultCardRules(),
		AlwaysPlayable:                       []int{2, 8},
		CanPlayAgain:                         []int{10},
	}
	if rawRules, present := requested["cardRules"]; present && rawRules != nil {
		rules, err := parseCardRules(rawRules)
		if err != nil {
			return GameConfig{}, err
		}
		cfg.CardRules = rules
		cfg.AlwaysPlayable = rankValues(rules, "JOKER", "TRANSPARENT")
		cfg.CanPlayAgain = rankValues(rules, "BURNER")
	}
	return cfg, nil
}

// burnCountFor is the pile size that burns: four cards with one deck, six with two.
func burnCountFor(decks int) int {
	if decks == 1 {
		return 4
	}
	return 6
}

// parseCardRules returns a rule for every rank 2..14, defaulting to DEFAULT.
func parseCardRules(raw any) (map[string]string, error) {
	requested, ok := raw.(map[string]any)
	if !ok {
		return nil, errCardRulesObject
	}
	rules := make(map[string]string, lastRank-firstRank+1)
	for rank := firstRank; rank <= lastRank; rank++ {
		name := strconv.Itoa(rank)
		rule := "DEFAULT"
		if value, present := requested[name]; present {
			text, isText := value.(string)
			if !isText || !validCardRuleValues[text] {
				return nil, errCardRuleValue
			}
			rule = text
		}
		rules[name] = rule
	}
	return rules, nil
}

// rankValues lists, in ascending order, the ranks whose rule is one of names.
func rankValues(rules map[string]string, names ...string) []int {
	values := make([]int, 0, len(rules))
	for rank := firstRank; rank <= lastRank; rank++ {
		rule := rules[strconv.Itoa(rank)]
		for _, name := range names {
			if rule == name {
				values = append(values, rank)
				break
			}
		}
	}
	return values
}

// intField reads an integer member, returning def when it is absent.
// Only JSON numbers with an integral value are accepted.
func intField(m map[string]any, name string, def, lo, hi int, rangeErr error) (int, error) {
	raw, present := m[name]
	if !present || raw == nil {
		return def, nil
	}
	number, ok := raw.(float64)
	if !ok || math.Trunc(number) != number {
		return 0, fmt.Errorf("%s: %w", name, rangeErr)
	}
	value := int(number)
	if value < lo || value > hi {
		return 0, fmt.Errorf("%s: %w", name, rangeErr)
	}
	return value, nil
}

// boolField reads a boolean member, returning false when it is absent.
func boolField(m map[string]any, name string) (bool, error) {
	raw, present := m[name]
	if !present || raw == nil {
		return false, nil
	}
	value, ok := raw.(bool)
	if !ok {
		return false, fmt.Errorf("%s: %w", name, errBoolField)
	}
	return value, nil
}
