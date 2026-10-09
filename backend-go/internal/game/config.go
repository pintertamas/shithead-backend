package game

import (
	"errors"
	"fmt"
	"math"
	"strconv"

	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

const (
	cardsPerDeck      = 52
	minCardValue      = 2
	maxCardValue      = 14
	maxZoneCount      = 10
	defaultZoneCount  = 3
	defaultDeckCount  = 1
	burnCountOneDeck  = 4
	burnCountTwoDecks = 6
)

// ErrInvalidConfig is returned when a requested game configuration is rejected.
var ErrInvalidConfig = errors.New("invalid game configuration")

// GameConfig describes the rules of one game. Maps are keyed by card value.
type GameConfig struct {
	DecksCount                           int
	FaceDownCount                        int
	FaceUpCount                          int
	HandCount                            int
	BurnCount                            int
	AllowMixedHandAndFaceUpWhenDeckEmpty bool
	AllowFailedFaceUpPlay                bool
	RulesByValue                         map[int]rules.CardRule
	AlwaysPlayableByValue                map[int]bool
	PlayAgainByValue                     map[int]bool
}

// DefaultConfig returns the standard one-deck game with the special cards.
func DefaultConfig() GameConfig {
	return GameConfig{
		DecksCount:    defaultDeckCount,
		FaceDownCount: defaultZoneCount,
		FaceUpCount:   defaultZoneCount,
		HandCount:     defaultZoneCount,
		BurnCount:     burnCountOneDeck,
		RulesByValue: map[int]rules.CardRule{
			2: rules.RuleJoker, 6: rules.RuleSmaller, 8: rules.RuleTransparent,
			9: rules.RuleReverse, 10: rules.RuleBurner,
		},
		AlwaysPlayableByValue: map[int]bool{2: true, 8: true},
		PlayAgainByValue:      map[int]bool{10: true},
	}
}

// RuleFor returns the rule for a card value (DEFAULT when unmapped).
func (c GameConfig) RuleFor(value int) rules.CardRule {
	if rule, ok := c.RulesByValue[value]; ok {
		return rule
	}
	return rules.RuleDefault
}

// IsAlwaysPlayable reports whether cards of this value bypass the rule checks.
func (c GameConfig) IsAlwaysPlayable(value int) bool {
	return c.AlwaysPlayableByValue[value]
}

// CanPlayAgain reports whether playing this value grants another turn.
func (c GameConfig) CanPlayAgain(value int) bool {
	return c.PlayAgainByValue[value]
}

// CardsPerPlayer is the number of cards dealt to each seat at the start.
func (c GameConfig) CardsPerPlayer() int {
	return c.FaceDownCount + c.FaceUpCount + c.HandCount
}

// ParseConfig validates a configuration object sent by the client. A nil
// value yields the default configuration. Unknown keys are ignored.
func ParseConfig(raw any) (GameConfig, error) {
	if raw == nil {
		return DefaultConfig(), nil
	}
	fields, ok := raw.(map[string]any)
	if !ok {
		return GameConfig{}, fmt.Errorf("%w: config must be an object", ErrInvalidConfig)
	}
	decks, err := intField(fields, "decksCount", defaultDeckCount, 1, 2)
	if err != nil {
		return GameConfig{}, err
	}
	config := DefaultConfig()
	config.DecksCount = decks
	config.BurnCount = burnCountOneDeck
	if decks == 2 {
		config.BurnCount = burnCountTwoDecks
	}
	if config.FaceDownCount, err = intField(fields, "faceDownCount", defaultZoneCount, 0, maxZoneCount); err != nil {
		return GameConfig{}, err
	}
	if config.FaceUpCount, err = intField(fields, "faceUpCount", defaultZoneCount, 0, maxZoneCount); err != nil {
		return GameConfig{}, err
	}
	if config.HandCount, err = intField(fields, "handCount", defaultZoneCount, 0, maxZoneCount); err != nil {
		return GameConfig{}, err
	}
	if err := checkFeasible(config); err != nil {
		return GameConfig{}, err
	}
	if config.AllowMixedHandAndFaceUpWhenDeckEmpty, err = boolField(fields, "allowMixedHandAndFaceUpWhenDeckEmpty"); err != nil {
		return GameConfig{}, err
	}
	if config.AllowFailedFaceUpPlay, err = boolField(fields, "allowFailedFaceUpPlay"); err != nil {
		return GameConfig{}, err
	}
	if _, present := fields["cardRules"]; present {
		if err := applyCardRules(&config, fields["cardRules"]); err != nil {
			return GameConfig{}, err
		}
	}
	return config, nil
}

// checkFeasible ensures at least two seats can be dealt from the chosen decks.
func checkFeasible(c GameConfig) error {
	if c.CardsPerPlayer()*2 > c.DecksCount*cardsPerDeck {
		return fmt.Errorf("%w: the selected zone sizes do not fit the deck", ErrInvalidConfig)
	}
	return nil
}

func intField(fields map[string]any, key string, fallback, minValue, maxValue int) (int, error) {
	raw, present := fields[key]
	if !present || raw == nil {
		return fallback, nil
	}
	number, ok := raw.(float64)
	if !ok || number != math.Trunc(number) || number < float64(minValue) || number > float64(maxValue) {
		return 0, fmt.Errorf("%w: %s must be an integer from %d to %d", ErrInvalidConfig, key, minValue, maxValue)
	}
	return int(number), nil
}

func boolField(fields map[string]any, key string) (bool, error) {
	raw, present := fields[key]
	if !present || raw == nil {
		return false, nil
	}
	value, ok := raw.(bool)
	if !ok {
		return false, fmt.Errorf("%w: %s must be a boolean", ErrInvalidConfig, key)
	}
	return value, nil
}

// applyCardRules replaces the rule table with the requested one. Values 2-14
// that are not mentioned default to DEFAULT. JOKER and TRANSPARENT ranks are
// always playable; BURNER ranks grant another turn.
func applyCardRules(config *GameConfig, raw any) error {
	requested, ok := raw.(map[string]any)
	if !ok {
		return fmt.Errorf("%w: cardRules must be an object", ErrInvalidConfig)
	}
	config.RulesByValue = make(map[int]rules.CardRule, maxCardValue-minCardValue+1)
	config.AlwaysPlayableByValue = map[int]bool{}
	config.PlayAgainByValue = map[int]bool{}
	for value := minCardValue; value <= maxCardValue; value++ {
		rule, err := ruleField(requested, strconv.Itoa(value))
		if err != nil {
			return err
		}
		config.RulesByValue[value] = rule
		if rule == rules.RuleJoker || rule == rules.RuleTransparent {
			config.AlwaysPlayableByValue[value] = true
		}
		if rule == rules.RuleBurner {
			config.PlayAgainByValue[value] = true
		}
	}
	return nil
}

func ruleField(requested map[string]any, key string) (rules.CardRule, error) {
	raw, present := requested[key]
	if !present || raw == nil {
		return rules.RuleDefault, nil
	}
	name, ok := raw.(string)
	if !ok || !rules.KnownRules[rules.CardRule(name)] {
		return "", fmt.Errorf("%w: cardRules contains an unsupported rule", ErrInvalidConfig)
	}
	return rules.CardRule(name), nil
}
