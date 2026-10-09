package game

import (
	"math/rand/v2"

	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

// Deck is an ordered draw pile. Cards are drawn from the front.
type Deck struct {
	cards []rules.Card
}

// NewShuffledDeck builds decks standard 52-card decks with the configured
// rules applied to every card, then shuffles them.
func NewShuffledDeck(decks int, config GameConfig) *Deck {
	cards := make([]rules.Card, 0, decks*cardsPerDeck)
	for d := 0; d < decks; d++ {
		for _, suit := range rules.Suits {
			for value := minCardValue; value <= maxCardValue; value++ {
				cards = append(cards, rules.Card{
					Suit:           suit,
					Value:          value,
					Rule:           config.RuleFor(value),
					AlwaysPlayable: config.IsAlwaysPlayable(value),
				})
			}
		}
	}
	rand.Shuffle(len(cards), func(i, j int) { cards[i], cards[j] = cards[j], cards[i] })
	return &Deck{cards: cards}
}

// NewDeck wraps an explicit card order (used when loading a stored game).
func NewDeck(cards []rules.Card) *Deck {
	return &Deck{cards: append([]rules.Card(nil), cards...)}
}

// Draw removes and returns the top card. It is safe to call on a nil deck.
func (d *Deck) Draw() (rules.Card, bool) {
	if d == nil || len(d.cards) == 0 {
		return rules.Card{}, false
	}
	card := d.cards[0]
	d.cards = d.cards[1:]
	return card, true
}

// Len is the number of cards left. It is safe to call on a nil deck.
func (d *Deck) Len() int {
	if d == nil {
		return 0
	}
	return len(d.cards)
}

// Cards returns a copy of the remaining cards in draw order.
func (d *Deck) Cards() []rules.Card {
	if d == nil {
		return nil
	}
	return append([]rules.Card(nil), d.cards...)
}
