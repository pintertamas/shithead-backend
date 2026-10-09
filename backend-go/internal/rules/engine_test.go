package rules

import "testing"

func card(suit Suit, value int, rule CardRule, alwaysPlayable bool) Card {
	return Card{Suit: suit, Value: value, Rule: rule, AlwaysPlayable: alwaysPlayable}
}

func def(value int) Card { return card(SuitHearts, value, RuleDefault, false) }

func TestCanPlay_emptyPile_acceptsAnyCard(t *testing.T) {
	// Given an empty pile
	// When/Then
	if !CanPlay(def(7), nil) {
		t.Fatal("expected any card to be playable on an empty pile")
	}
}

func TestCanPlay_alwaysPlayableCard_ignoresTopCard(t *testing.T) {
	// Given a joker on a king
	joker := card(SuitHearts, 7, RuleDefault, true)
	pile := []Card{def(13)}

	// When/Then
	if !CanPlay(joker, pile) {
		t.Fatal("always-playable card must be accepted")
	}
}

func TestCanPlay_defaultTop_requiresEqualOrHigher(t *testing.T) {
	pile := []Card{def(9)}
	if CanPlay(def(3), pile) {
		t.Fatal("3 must not be playable on 9")
	}
	if !CanPlay(def(9), pile) || !CanPlay(def(10), pile) {
		t.Fatal("9 and 10 must be playable on 9")
	}
}

func TestCanPlay_smallerTop_requiresEqualOrLower(t *testing.T) {
	// Given a 6 (SMALLER) on top
	pile := []Card{card(SuitClubs, 6, RuleSmaller, false)}

	// When/Then
	if !CanPlay(def(4), pile) || !CanPlay(def(6), pile) {
		t.Fatal("4 and 6 must be playable on a smaller-rule 6")
	}
	if CanPlay(def(9), pile) {
		t.Fatal("9 must not be playable on a smaller-rule 6")
	}
}

func TestCanPlay_jokerOrBurnerTop_allowsAnything(t *testing.T) {
	if !CanPlay(def(3), []Card{card(SuitClubs, 2, RuleJoker, true)}) {
		t.Fatal("anything is playable on a joker")
	}
	if !CanPlay(def(3), []Card{card(SuitClubs, 10, RuleBurner, false)}) {
		t.Fatal("anything is playable on a burner")
	}
}

func TestCanPlay_transparentTop_usesCardBelow(t *testing.T) {
	// Given 5 then a transparent 8
	pile := []Card{def(5), card(SuitSpades, 8, RuleTransparent, false)}

	// When/Then the 5 below decides
	if !CanPlay(def(9), pile) {
		t.Fatal("9 should be playable through a transparent 8 onto a 5")
	}
	if CanPlay(def(3), pile) {
		t.Fatal("3 should not be playable through a transparent 8 onto a 5")
	}
}

func TestCanPlay_transparentOverKing_queenRejectedAceAccepted(t *testing.T) {
	pile := []Card{def(13), card(SuitSpades, 8, RuleTransparent, false)}

	if CanPlay(def(12), pile) {
		t.Fatal("queen must not be playable on a king through a transparent 8")
	}
	if !CanPlay(def(14), pile) {
		t.Fatal("ace must be playable on a king through a transparent 8")
	}
}

func TestCanPlay_walksThroughMultipleTransparentCards(t *testing.T) {
	pile := []Card{
		def(5),
		card(SuitSpades, 8, RuleTransparent, false),
		card(SuitDiamonds, 8, RuleTransparent, false),
	}
	if !CanPlay(def(5), pile) {
		t.Fatal("5 should be playable through two transparent cards onto a 5")
	}
	if CanPlay(def(3), pile) {
		t.Fatal("3 should not be playable through two transparent cards onto a 5")
	}
}

func TestCanPlay_allTransparentPile_isAlwaysPlayable(t *testing.T) {
	pile := []Card{
		card(SuitHearts, 8, RuleTransparent, false),
		card(SuitSpades, 8, RuleTransparent, false),
	}
	if !CanPlay(def(3), pile) {
		t.Fatal("an all-transparent pile accepts any card")
	}
}

func TestCanPlay_transparentTop_alwaysPlayableIgnoresRule(t *testing.T) {
	pile := []Card{def(5), card(SuitSpades, 8, RuleTransparent, false)}
	joker := card(SuitClubs, 2, RuleJoker, true)
	if !CanPlay(joker, pile) {
		t.Fatal("always-playable card ignores the transparent rule")
	}
}

func TestShouldBurn_fourMatchingTopCards_burns(t *testing.T) {
	pile := []Card{def(7), card(SuitDiamonds, 7, RuleDefault, false), card(SuitClubs, 7, RuleDefault, false), card(SuitSpades, 7, RuleDefault, false)}
	if !ShouldBurn(pile, 4) {
		t.Fatal("four equal top cards must burn at count 4")
	}
}

func TestShouldBurn_differentValues_doesNotBurn(t *testing.T) {
	pile := []Card{def(7), def(8), def(9), def(10)}
	if ShouldBurn(pile, 4) {
		t.Fatal("different values must not burn")
	}
}

func TestShouldBurn_fewerThanNMatching_doesNotBurn(t *testing.T) {
	pile := []Card{def(5), def(7), def(7), def(7)}
	if ShouldBurn(pile, 4) {
		t.Fatal("three matching cards must not burn at count 4")
	}
}

func TestShouldBurn_sixAtTwoDecks_burns(t *testing.T) {
	pile := []Card{def(4), def(4), def(4), def(4), def(4), def(4)}
	if !ShouldBurn(pile, 6) {
		t.Fatal("six equal cards must burn at count 6")
	}
}

func TestShouldBurn_emptyPile_doesNotBurn(t *testing.T) {
	if ShouldBurn(nil, 4) {
		t.Fatal("an empty pile must not burn")
	}
}

func TestAfterEffect_burner_clearsPile(t *testing.T) {
	pile := []Card{def(5), def(7)}
	burner := card(SuitClubs, 10, RuleBurner, false)

	got := AfterEffect(burner, pile, &Player{}, nil)

	if len(got) != 0 {
		t.Fatalf("burner must clear the pile, got %d cards", len(got))
	}
}

func TestAfterEffect_defaultCard_isNoOp(t *testing.T) {
	pile := []Card{def(5)}
	alice := &Player{PlayerID: "a"}
	bob := &Player{PlayerID: "b"}
	players := []*Player{alice, bob}

	got := AfterEffect(def(7), pile, alice, players)

	if len(got) != 1 || players[0] != alice || players[1] != bob {
		t.Fatal("default card must not change pile or seating")
	}
}

func TestAfterEffect_reverse_reversesSeatingAroundCurrentPlayer(t *testing.T) {
	// Given alice, bob, carol with alice playing the reverse
	alice := &Player{PlayerID: "alice"}
	bob := &Player{PlayerID: "bob"}
	carol := &Player{PlayerID: "carol"}
	players := []*Player{alice, bob, carol}

	// When
	AfterEffect(card(SuitClubs, 9, RuleReverse, false), nil, alice, players)

	// Then alice stays first and carol follows her
	if players[0] != alice || players[1] != carol || players[2] != bob {
		t.Fatalf("unexpected order: %s %s %s", players[0].PlayerID, players[1].PlayerID, players[2].PlayerID)
	}
}

func TestReversePlayers_twoPlayers_keepsOrder(t *testing.T) {
	// With two seats, anchoring on the current player leaves the order unchanged.
	a := &Player{PlayerID: "a"}
	b := &Player{PlayerID: "b"}
	players := []*Player{a, b}

	ReversePlayers(players, b)

	if players[0] != a || players[1] != b {
		t.Fatalf("two-seat reverse should keep the order, got %s %s", players[0].PlayerID, players[1].PlayerID)
	}
}

func TestReversePlayers_currentNotInList_isNoOp(t *testing.T) {
	a := &Player{PlayerID: "a"}
	b := &Player{PlayerID: "b"}
	players := []*Player{a, b}

	ReversePlayers(players, &Player{PlayerID: "ghost"})

	if players[0] != a || players[1] != b {
		t.Fatal("a player outside the table must not reorder seats")
	}
}
