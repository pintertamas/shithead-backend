package game

import (
	"testing"

	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

// cardOf returns a hearts card carrying the default config rule for value.
func cardOf(value int) rules.Card {
	cfg := DefaultConfig()
	return rules.Card{Suit: rules.SuitHearts, Value: value, Rule: cfg.RuleFor(value), AlwaysPlayable: cfg.IsAlwaysPlayable(value)}
}

func plain(suit rules.Suit, value int) rules.Card {
	return rules.Card{Suit: suit, Value: value, Rule: rules.RuleDefault}
}

func newTwoPlayerSession(t *testing.T) *Session {
	t.Helper()
	s := NewSession("test-session", "p1", DefaultConfig())
	if err := s.AddPlayer("p1", "alice"); err != nil {
		t.Fatal(err)
	}
	if err := s.AddPlayer("p2", "bob"); err != nil {
		t.Fatal(err)
	}
	return s
}

// prepareStarted marks the session as playing with an empty deck (no refills).
func prepareStarted(s *Session) {
	s.Started = true
	s.Deck = NewDeck(nil)
}

func isSorted(cards []rules.Card) bool {
	for i := 1; i < len(cards); i++ {
		a, b := cards[i-1], cards[i]
		if a.Value > b.Value || (a.Value == b.Value && string(a.Suit) > string(b.Suit)) {
			return false
		}
	}
	return true
}

func TestStart_dealsThreeCardsToEachZone(t *testing.T) {
	// Given two seated players
	s := newTwoPlayerSession(t)

	// When
	if err := s.Start(); err != nil {
		t.Fatal(err)
	}

	// Then
	for _, p := range s.Players {
		if len(p.FaceDown) != 3 || len(p.FaceUp) != 3 || len(p.Hand) != 3 {
			t.Fatalf("expected 3/3/3 cards, got %d/%d/%d", len(p.FaceDown), len(p.FaceUp), len(p.Hand))
		}
	}
	if !s.Started {
		t.Fatal("start must set the started flag")
	}
}

func TestStart_opensSetupAndKeepsFaceDownOrder(t *testing.T) {
	s := newTwoPlayerSession(t)
	if err := s.Start(); err != nil {
		t.Fatal(err)
	}
	p := s.Players[0]
	before := append([]rules.Card(nil), p.FaceDown...)

	if s.SetupComplete || p.Ready {
		t.Fatal("setup must be open and players not ready after start")
	}
	for i := range before {
		if before[i] != p.FaceDown[i] {
			t.Fatal("face-down order must be preserved")
		}
	}
	if !isSorted(p.Hand) || !isSorted(p.FaceUp) {
		t.Fatal("hand and face-up zones must be sorted after the deal")
	}
	if r := s.PlayCards([]rules.Card{p.Hand[0]}); r != ResultInvalid {
		t.Fatalf("play must be blocked during setup, got %v", r)
	}
}

func TestSwapAndReadiness_gatePlay(t *testing.T) {
	s := newTwoPlayerSession(t)
	if err := s.Start(); err != nil {
		t.Fatal(err)
	}
	alice := s.Players[0]
	handCard := alice.Hand[0]
	faceUpCard := alice.FaceUp[0]
	faceDown := append([]rules.Card(nil), alice.FaceDown...)

	if !s.SwapStartingCards("p1", 0, 0) {
		t.Fatal("swap should be accepted during setup")
	}
	if !contains(alice.Hand, faceUpCard) || !contains(alice.FaceUp, handCard) {
		t.Fatal("swap must exchange the two cards")
	}
	for i := range faceDown {
		if faceDown[i] != alice.FaceDown[i] {
			t.Fatal("swap must not touch face-down cards")
		}
	}
	if !s.MarkReady("p1") || s.SetupComplete {
		t.Fatal("setup stays open until everyone is ready")
	}
	if s.SwapStartingCards("p1", 0, 0) {
		t.Fatal("a ready player must not swap again")
	}
	if !s.MarkReady("p2") || !s.SetupComplete {
		t.Fatal("setup must complete when all players are ready")
	}
}

func contains(cards []rules.Card, c rules.Card) bool {
	for _, have := range cards {
		if have == c {
			return true
		}
	}
	return false
}

func TestAddPlayer_afterStart_fails(t *testing.T) {
	s := newTwoPlayerSession(t)
	if err := s.Start(); err != nil {
		t.Fatal(err)
	}
	if err := s.AddPlayer("p3", "carol"); err == nil {
		t.Fatal("adding a player after start must fail")
	}
}

func TestStart_notEnoughCards_fails(t *testing.T) {
	cfg := DefaultConfig()
	cfg.FaceDownCount, cfg.FaceUpCount, cfg.HandCount = 10, 10, 10
	s := NewSession("big", "p1", cfg)
	_ = s.AddPlayer("p1", "a")
	_ = s.AddPlayer("p2", "b")

	if err := s.Start(); err == nil {
		t.Fatal("two seats with 30 cards each cannot fit one deck")
	}
}

func TestPlayFromHand_validCard_succeeds(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	playable := cardOf(7)
	alice.Hand = append(alice.Hand, playable)
	s.Discard = append(s.Discard, cardOf(5))

	if r := s.PlayCards([]rules.Card{playable}); r != ResultSuccess {
		t.Fatalf("expected success, got %v", r)
	}
	if len(alice.Hand) != 0 {
		t.Fatal("played card must leave the hand")
	}
	if s.CurrentPlayerID() != "p2" {
		t.Fatalf("turn must advance to p2, got %s", s.CurrentPlayerID())
	}
}

func TestPlayFromHand_sameValueCards_allMoveToPile(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	c1, c2 := plain(rules.SuitHearts, 7), plain(rules.SuitSpades, 7)
	alice.Hand = append(alice.Hand, c1, c2, plain(rules.SuitClubs, 4))
	s.Discard = append(s.Discard, cardOf(5))

	if r := s.PlayCards([]rules.Card{c1, c2}); r != ResultSuccess {
		t.Fatalf("expected success, got %v", r)
	}
	if len(s.Discard) != 3 {
		t.Fatalf("both sevens must join the pile, got %d cards", len(s.Discard))
	}
}

func TestPlayFromHand_lowerOnHigher_invalid(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	low := cardOf(3)
	s.Players[0].Hand = append(s.Players[0].Hand, low)
	s.Discard = append(s.Discard, cardOf(9))

	if r := s.PlayCards([]rules.Card{low}); r != ResultInvalid {
		t.Fatalf("expected invalid, got %v", r)
	}
	if len(s.Players[0].Hand) != 1 {
		t.Fatal("rejected card must stay in hand")
	}
}

func TestPlayFromHand_mixedValues_invalid(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	c1, c2 := cardOf(5), cardOf(7)
	s.Players[0].Hand = append(s.Players[0].Hand, c1, c2)

	if r := s.PlayCards([]rules.Card{c1, c2}); r != ResultInvalid {
		t.Fatalf("expected invalid, got %v", r)
	}
}

func TestPlayFromHand_cardNotInHand_invalid(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	s.Players[0].Hand = append(s.Players[0].Hand, cardOf(5))

	if r := s.PlayCards([]rules.Card{cardOf(7)}); r != ResultInvalid {
		t.Fatalf("expected invalid, got %v", r)
	}
}

func TestPlayFromFaceUp_handEmpty_succeeds(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	faceUp := cardOf(7)
	alice.FaceUp = append(alice.FaceUp, faceUp)
	alice.FaceDown = append(alice.FaceDown, cardOf(5))
	s.Discard = append(s.Discard, cardOf(5))

	if r := s.PlayCards([]rules.Card{faceUp}); r != ResultSuccess {
		t.Fatalf("expected success, got %v", r)
	}
	if len(alice.FaceUp) != 0 {
		t.Fatal("played face-up card must leave the zone")
	}
}

func TestPlayFromFaceUp_handNotEmpty_invalid(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	alice.Hand = append(alice.Hand, cardOf(5))
	alice.FaceUp = append(alice.FaceUp, cardOf(7))

	if r := s.PlayCards([]rules.Card{cardOf(7)}); r != ResultInvalid {
		t.Fatalf("expected invalid, got %v", r)
	}
}

func TestPlayFromFaceDown_successfulFlip_succeeds(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	flip := plain(rules.SuitClubs, 7)
	alice.FaceDown = append(alice.FaceDown, flip, plain(rules.SuitSpades, 9))
	s.Discard = append(s.Discard, cardOf(5))

	if r := s.PlayCards([]rules.Card{flip}); r != ResultSuccess {
		t.Fatalf("expected success, got %v", r)
	}
	if len(alice.FaceDown) != 1 {
		t.Fatal("the other face-down card must remain")
	}
}

func TestPlayFromFaceDown_failedFlip_playerTakesFlippedCardAndPile(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	flip := cardOf(3)
	pileCard := cardOf(9)
	alice.FaceDown = append(alice.FaceDown, flip)
	s.Discard = append(s.Discard, pileCard)

	if r := s.PlayCards([]rules.Card{flip}); r != ResultPickup {
		t.Fatalf("expected pickup, got %v", r)
	}
	if !contains(alice.Hand, flip) || !contains(alice.Hand, pileCard) {
		t.Fatal("flipped card and pile must move to the hand")
	}
	if len(s.Discard) != 0 || len(alice.FaceDown) != 0 {
		t.Fatal("pile and face-down zone must be empty after a failed flip")
	}
}

func TestFourOfAKindBurn_clearsPileAndGrantsReplay(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	s.Discard = append(s.Discard, plain(rules.SuitHearts, 7), plain(rules.SuitDiamonds, 7), plain(rules.SuitClubs, 7))
	fourth := plain(rules.SuitSpades, 7)
	alice.Hand = append(alice.Hand, fourth, cardOf(4))

	if r := s.PlayCards([]rules.Card{fourth}); r != ResultSuccess {
		t.Fatalf("expected success, got %v", r)
	}
	if len(s.Discard) != 0 {
		t.Fatal("four equal cards must burn the pile")
	}
	if s.CurrentPlayerID() != "p1" {
		t.Fatal("a burn grants the same player another turn")
	}
}

func TestBurnerCard_clearsPileAndGrantsReplay(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	burner := cardOf(10)
	alice.Hand = append(alice.Hand, burner, cardOf(3))
	s.Discard = append(s.Discard, cardOf(5))

	if r := s.PlayCards([]rules.Card{burner}); r != ResultSuccess {
		t.Fatalf("expected success, got %v", r)
	}
	if len(s.Discard) != 0 || s.CurrentPlayerID() != "p1" {
		t.Fatal("burner clears the pile and keeps the turn")
	}
}

func TestBurnerCard_playerGoesOut_turnAdvances(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	burner := cardOf(10)
	alice.Hand = append(alice.Hand, burner)
	s.Players[1].Hand = append(s.Players[1].Hand, cardOf(5))

	s.PlayCards([]rules.Card{burner})

	if !alice.Out {
		t.Fatal("alice must be out")
	}
	if s.CurrentPlayerID() != "p2" {
		t.Fatalf("turn must advance to p2, got %s", s.CurrentPlayerID())
	}
}

func TestReverseCard_reversesOrderAndAdvancesTurn(t *testing.T) {
	s := NewSession("reverse", "p1", DefaultConfig())
	for _, seat := range []struct{ id, name string }{{"p1", "alice"}, {"p2", "bob"}, {"p3", "carol"}} {
		_ = s.AddPlayer(seat.id, seat.name)
	}
	prepareStarted(s)
	alice := s.Players[0]
	reverse := rules.Card{Suit: rules.SuitClubs, Value: 9, Rule: rules.RuleReverse}
	alice.Hand = append(alice.Hand, reverse, cardOf(5))

	s.PlayCards([]rules.Card{reverse})

	if s.Players[0].PlayerID != "p1" || s.Players[1].PlayerID != "p3" || s.Players[2].PlayerID != "p2" {
		t.Fatal("reverse must reorder seats around alice")
	}
	if s.CurrentPlayerID() != "p3" {
		t.Fatalf("carol must take the next turn, got %s", s.CurrentPlayerID())
	}
}

func TestReverseCard_playedByMiddlePlayer_turnSequence(t *testing.T) {
	// Given alice, bob, carol with bob about to play a reverse
	s := NewSession("reverse-middle", "p1", DefaultConfig())
	for _, seat := range []struct{ id, name string }{{"p1", "alice"}, {"p2", "bob"}, {"p3", "carol"}} {
		_ = s.AddPlayer(seat.id, seat.name)
	}
	prepareStarted(s)
	s.CurrentIndex = 1
	ace := plain(rules.SuitClubs, 14)
	reverse := rules.Card{Suit: rules.SuitClubs, Value: 9, Rule: rules.RuleReverse}
	s.Players[0].Hand = []rules.Card{ace, ace, ace}
	s.Players[1].Hand = []rules.Card{reverse, ace, ace}
	s.Players[2].Hand = []rules.Card{ace, ace, ace}

	// When/Then the order becomes alice, carol, bob
	if r := s.PlayCards([]rules.Card{reverse}); r != ResultSuccess {
		t.Fatalf("reverse should succeed, got %v", r)
	}
	if s.CurrentPlayerID() != "p1" {
		t.Fatalf("expected p1, got %s", s.CurrentPlayerID())
	}
	s.PlayCards([]rules.Card{ace})
	if s.CurrentPlayerID() != "p3" {
		t.Fatalf("expected p3, got %s", s.CurrentPlayerID())
	}
	s.PlayCards([]rules.Card{ace})
	if s.CurrentPlayerID() != "p2" {
		t.Fatalf("expected p2, got %s", s.CurrentPlayerID())
	}
}

func TestHandRefillsFromDeck_afterPlay(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	playable := cardOf(7)
	alice.Hand = append(alice.Hand, playable)
	s.Deck = NewDeck([]rules.Card{plain(rules.SuitClubs, 3), plain(rules.SuitDiamonds, 4)})
	s.Discard = append(s.Discard, cardOf(5))

	s.PlayCards([]rules.Card{playable})

	if len(alice.Hand) != 2 {
		t.Fatalf("hand should refill to the two cards left in the deck, got %d", len(alice.Hand))
	}
}

func TestPlayerBecomesOut_whenAllCardsGone(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	last := cardOf(7)
	alice.Hand = append(alice.Hand, last)
	s.Discard = append(s.Discard, cardOf(5))

	s.PlayCards([]rules.Card{last})

	if !alice.Out {
		t.Fatal("alice has no cards left and must be out")
	}
}

func TestNextPlayer_skipsOutPlayers(t *testing.T) {
	s := NewSession("skip", "p1", DefaultConfig())
	for _, seat := range []struct{ id, name string }{{"p1", "alice"}, {"p2", "bob"}, {"p3", "carol"}} {
		_ = s.AddPlayer(seat.id, seat.name)
	}
	prepareStarted(s)
	s.Players[1].Out = true
	alice := s.Players[0]
	playable := cardOf(7)
	alice.Hand = append(alice.Hand, playable, cardOf(5))
	s.Discard = append(s.Discard, cardOf(5))

	s.PlayCards([]rules.Card{playable})

	if s.CurrentPlayerID() != "p3" {
		t.Fatalf("turn must skip bob and land on carol, got %s", s.CurrentPlayerID())
	}
}

func TestGameEnds_whenOnePlayerRemains_setsShithead(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	alice := s.Players[0]
	s.Players[1].Hand = append(s.Players[1].Hand, cardOf(4))
	last := cardOf(7)
	alice.Hand = append(alice.Hand, last)
	s.Discard = append(s.Discard, cardOf(5))

	s.PlayCards([]rules.Card{last})

	if !s.Finished || s.ShitheadID != "p2" {
		t.Fatalf("expected finished with shithead p2, got finished=%v shithead=%q", s.Finished, s.ShitheadID)
	}
}

func TestFinishedGame_rejectsPlayAndPickup(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	s.Finished = true
	s.Discard = append(s.Discard, cardOf(5))

	if s.PlayCards([]rules.Card{cardOf(7)}) != ResultInvalid {
		t.Fatal("play must be rejected after the game ends")
	}
	if s.PickupPile() != ResultInvalid {
		t.Fatal("pickup must be rejected after the game ends")
	}
}

func TestAlwaysPlayableJoker_playsOnHighPile(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	joker := cardOf(2)
	s.Players[0].Hand = append(s.Players[0].Hand, joker, cardOf(4))
	s.Discard = append(s.Discard, cardOf(13))

	if r := s.PlayCards([]rules.Card{joker}); r != ResultSuccess {
		t.Fatalf("a joker must be playable on a king, got %v", r)
	}
}

func TestSmallerCard_enforcesDescendingRule(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	p := s.Players[0]
	s.Discard = append(s.Discard, rules.Card{Suit: rules.SuitClubs, Value: 6, Rule: rules.RuleSmaller})
	valid, invalid := cardOf(4), cardOf(9)
	p.Hand = append(p.Hand, valid, invalid, cardOf(3))

	if s.PlayCards([]rules.Card{invalid}) != ResultInvalid {
		t.Fatal("9 must be rejected on a smaller-rule 6")
	}
	if s.PlayCards([]rules.Card{valid}) != ResultSuccess {
		t.Fatal("4 must be accepted on a smaller-rule 6")
	}
}

func TestCurrentPlayerID_noPlayers_isEmpty(t *testing.T) {
	if id := NewSession("empty", "", DefaultConfig()).CurrentPlayerID(); id != "" {
		t.Fatalf("expected empty id, got %q", id)
	}
}

func TestPickupPile_movesDiscardToHandAndAdvances(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	c1, c2 := cardOf(5), cardOf(9)
	s.Discard = append(s.Discard, c1, c2)

	if s.PickupPile() != ResultPickup {
		t.Fatal("pickup should succeed with a non-empty pile")
	}
	if len(s.Discard) != 0 || !contains(s.Players[0].Hand, c1) || !contains(s.Players[0].Hand, c2) {
		t.Fatal("pile must move to the picker's hand")
	}
	if s.CurrentPlayerID() != "p2" {
		t.Fatal("pickup must end the turn")
	}
}

func TestPlaySelections_mixedHandAndFaceUpWithEmptyDeck_succeedsWhenAllowed(t *testing.T) {
	cfg := DefaultConfig()
	cfg.AllowMixedHandAndFaceUpWhenDeckEmpty = true
	s := NewSession("mixed", "p1", cfg)
	_ = s.AddPlayer("p1", "alice")
	_ = s.AddPlayer("p2", "bob")
	prepareStarted(s)
	alice := s.Players[0]
	alice.Hand = []rules.Card{cardOf(7)}
	alice.FaceUp = []rules.Card{cardOf(7)}
	alice.FaceDown = []rules.Card{cardOf(3)}
	s.Discard = append(s.Discard, cardOf(5))

	result := s.PlaySelections([]CardSelection{{Source: SourceHand, Index: 0}, {Source: SourceFaceUp, Index: 0}})

	if result != ResultSuccess {
		t.Fatalf("expected success, got %v", result)
	}
	if len(alice.Hand) != 0 || len(alice.FaceUp) != 0 {
		t.Fatal("both selected cards must leave their zones")
	}
}

func TestPlaySelections_mixedHandAndFaceUpNotAllowed_invalid(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	s.Players[0].Hand = []rules.Card{cardOf(7)}
	s.Players[0].FaceUp = []rules.Card{cardOf(7)}

	if s.PlaySelections([]CardSelection{{Source: SourceHand, Index: 0}, {Source: SourceFaceUp, Index: 0}}) != ResultInvalid {
		t.Fatal("mixed play requires the config flag and an empty deck")
	}
}

func TestPlaySelections_faceDownWithAnotherSelection_invalid(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	s.Players[0].FaceDown = []rules.Card{cardOf(7)}
	s.Players[0].Hand = []rules.Card{cardOf(9)}

	if s.PlaySelections([]CardSelection{{Source: SourceFaceDown, Index: 0}, {Source: SourceHand, Index: 0}}) != ResultInvalid {
		t.Fatal("a face-down selection must be played alone")
	}
}

func TestPlaySelections_duplicateOrOutOfRange_invalid(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	s.Players[0].Hand = []rules.Card{cardOf(7)}

	if s.PlaySelections([]CardSelection{{Source: SourceHand, Index: 0}, {Source: SourceHand, Index: 0}}) != ResultInvalid {
		t.Fatal("duplicate selections must be rejected")
	}
	if s.PlaySelections([]CardSelection{{Source: SourceHand, Index: 4}}) != ResultInvalid {
		t.Fatal("out-of-range index must be rejected")
	}
	if s.PlaySelections([]CardSelection{{Source: CardSource("BOGUS"), Index: 0}}) != ResultInvalid {
		t.Fatal("unknown zone must be rejected")
	}
}

func TestPlaySelections_handCard_playsAndAdvances(t *testing.T) {
	s := newTwoPlayerSession(t)
	prepareStarted(s)
	s.Players[0].Hand = []rules.Card{cardOf(7), cardOf(4)}
	s.Discard = append(s.Discard, cardOf(5))

	if r := s.PlaySelections([]CardSelection{{Source: SourceHand, Index: 0}}); r != ResultSuccess {
		t.Fatalf("expected success, got %v", r)
	}
	if s.CurrentPlayerID() != "p2" {
		t.Fatal("turn must advance after a successful selection play")
	}
}
