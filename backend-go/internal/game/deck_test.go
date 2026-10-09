package game

import "testing"

func drainCount(d *Deck) int {
	n := 0
	for {
		if _, ok := d.Draw(); !ok {
			return n
		}
		n++
	}
}

func TestNewShuffledDeck_oneDeck_has52Cards(t *testing.T) {
	deck := NewShuffledDeck(1, DefaultConfig())

	if got := drainCount(deck); got != 52 {
		t.Fatalf("expected 52 cards, got %d", got)
	}
}

func TestNewShuffledDeck_twoDecks_has104Cards(t *testing.T) {
	deck := NewShuffledDeck(2, DefaultConfig())

	if got := drainCount(deck); got != 104 {
		t.Fatalf("expected 104 cards, got %d", got)
	}
}

func TestNewShuffledDeck_appliesRulesToEveryCard(t *testing.T) {
	deck := NewShuffledDeck(1, DefaultConfig())

	for _, c := range deck.Cards() {
		if c.Value == 10 && c.Rule != "BURNER" {
			t.Fatalf("ten must carry the BURNER rule, got %s", c.Rule)
		}
		if c.Value == 2 && !c.AlwaysPlayable {
			t.Fatal("two must be always playable")
		}
	}
}

func TestDeck_emptyDeck_drawReturnsFalse(t *testing.T) {
	deck := NewDeck(nil)

	if _, ok := deck.Draw(); ok {
		t.Fatal("drawing from an empty deck must return false")
	}
}

func TestDeck_nilDeck_isSafe(t *testing.T) {
	var deck *Deck

	if _, ok := deck.Draw(); ok || deck.Len() != 0 {
		t.Fatal("a nil deck must behave as empty")
	}
}
