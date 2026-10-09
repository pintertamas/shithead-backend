// Package rules holds the card value types and the stateless rule engine.
// It is a leaf package: the game package builds on top of it.
package rules

// Suit is a card suit. The string values match the DynamoDB and JSON format.
type Suit string

const (
	SuitClubs    Suit = "CLUBS"
	SuitHearts   Suit = "HEARTS"
	SuitSpades   Suit = "SPADES"
	SuitDiamonds Suit = "DIAMONDS"
)

// Suits lists every suit in the order used when building a deck.
var Suits = []Suit{SuitClubs, SuitHearts, SuitSpades, SuitDiamonds}

// CardRule is the special behaviour attached to a card value.
type CardRule string

const (
	RuleDefault     CardRule = "DEFAULT"
	RuleJoker       CardRule = "JOKER"
	RuleSmaller     CardRule = "SMALLER"
	RuleTransparent CardRule = "TRANSPARENT"
	RuleReverse     CardRule = "REVERSE"
	RuleBurner      CardRule = "BURNER"
)

// KnownRules is the set of rule names accepted in a game configuration.
var KnownRules = map[CardRule]bool{
	RuleDefault: true, RuleJoker: true, RuleSmaller: true,
	RuleTransparent: true, RuleReverse: true, RuleBurner: true,
}

// Card is a playing card. JSON field names match the existing API contract.
type Card struct {
	Suit           Suit     `json:"suit" dynamodbav:"suit"`
	Value          int      `json:"value" dynamodbav:"value"`
	Rule           CardRule `json:"rule" dynamodbav:"rule"`
	AlwaysPlayable bool     `json:"alwaysPlayable" dynamodbav:"alwaysPlayable"`
}

// Player is one seat in a game. Hand, FaceUp and FaceDown are ordered zones.
type Player struct {
	PlayerID string
	Username string
	Hand     []Card
	FaceUp   []Card
	FaceDown []Card
	Ready    bool
	Out      bool
}
