// Package game holds the in-memory state machine for one Shithead table.
// It is a faithful port of the Java GameSession: same turn order, burns,
// replays, blind flips, setup swaps and game end.
package game

import (
	"errors"
	"sort"

	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

// Errors returned by lobby operations.
var (
	ErrGameStarted    = errors.New("game already started")
	ErrNotEnoughCards = errors.New("not enough cards in the selected deck count")
	ErrDealShortfall  = errors.New("deck ran out while dealing")
)

// PlayResult is the outcome of a play or pickup attempt.
type PlayResult int

const (
	// ResultInvalid means the move was rejected and nothing changed.
	ResultInvalid PlayResult = iota
	// ResultSuccess means cards were played and after-effects applied.
	ResultSuccess
	// ResultPickup means the player took the pile (explicitly or after a failed blind flip).
	ResultPickup
)

// CardSource identifies the zone a selected card comes from.
type CardSource string

const (
	SourceHand     CardSource = "HAND"
	SourceFaceUp   CardSource = "FACE_UP"
	SourceFaceDown CardSource = "FACE_DOWN"
)

// CardSelection points at one card: a zone and an index inside it.
type CardSelection struct {
	Source CardSource `json:"source"`
	Index  int        `json:"index"`
}

// Session is the mutable game state. Callers persist it through the store.
type Session struct {
	ID             string
	OwnerID        string
	Players        []*rules.Player
	Discard        []rules.Card
	Deck           *Deck
	CurrentIndex   int
	Config         GameConfig
	Started        bool
	SetupComplete  bool
	Finished       bool
	ShitheadID     string
	lastPlayBurned bool
}

// NewSession creates a lobby with no players. Setup starts complete, as in the
// Java model, and is reopened by Start.
func NewSession(id, ownerID string, config GameConfig) *Session {
	return &Session{
		ID:            id,
		OwnerID:       ownerID,
		Config:        config,
		SetupComplete: true,
		Discard:       []rules.Card{},
	}
}

// AddPlayer seats a new player. Only allowed before the deal.
func (s *Session) AddPlayer(id, name string) error {
	if s.Started {
		return ErrGameStarted
	}
	s.Players = append(s.Players, &rules.Player{PlayerID: id, Username: name, Ready: true})
	return nil
}

// RemovePlayer removes a seat from the lobby. If the owner leaves, the first
// remaining player becomes owner.
func (s *Session) RemovePlayer(id string) error {
	if s.Started {
		return errors.New("cannot leave a started game")
	}
	kept := s.Players[:0]
	for _, p := range s.Players {
		if p.PlayerID != id {
			kept = append(kept, p)
		}
	}
	s.Players = kept
	if s.OwnerID == id && len(s.Players) > 0 {
		s.OwnerID = s.Players[0].PlayerID
	}
	return nil
}

// Start deals every seat and opens the card-swap and readiness phase.
func (s *Session) Start() error {
	if len(s.Players)*s.Config.CardsPerPlayer() > s.Config.DecksCount*cardsPerDeck {
		return ErrNotEnoughCards
	}
	s.Deck = NewShuffledDeck(s.Config.DecksCount, s.Config)
	for _, p := range s.Players {
		if err := s.deal(p); err != nil {
			return err
		}
		sortCards(p.Hand)
		sortCards(p.FaceUp)
		p.Ready = false
	}
	s.Started = true
	s.SetupComplete = false
	return nil
}

func (s *Session) deal(p *rules.Player) error {
	for i := 0; i < s.Config.FaceDownCount; i++ {
		c, ok := s.Deck.Draw()
		if !ok {
			return ErrDealShortfall
		}
		p.FaceDown = append(p.FaceDown, c)
	}
	for i := 0; i < s.Config.FaceUpCount; i++ {
		c, ok := s.Deck.Draw()
		if !ok {
			return ErrDealShortfall
		}
		p.FaceUp = append(p.FaceUp, c)
	}
	for i := 0; i < s.Config.HandCount; i++ {
		c, ok := s.Deck.Draw()
		if !ok {
			return ErrDealShortfall
		}
		p.Hand = append(p.Hand, c)
	}
	return nil
}

// SwapStartingCards exchanges one hand card with one face-up card during setup.
func (s *Session) SwapStartingCards(playerID string, handIndex, faceUpIndex int) bool {
	p := s.findPlayer(playerID)
	if !s.Started || s.SetupComplete || p == nil || p.Ready ||
		handIndex < 0 || faceUpIndex < 0 ||
		handIndex >= len(p.Hand) || faceUpIndex >= len(p.FaceUp) {
		return false
	}
	p.Hand[handIndex], p.FaceUp[faceUpIndex] = p.FaceUp[faceUpIndex], p.Hand[handIndex]
	sortCards(p.Hand)
	sortCards(p.FaceUp)
	return true
}

// MarkReady locks a player's setup. Play opens once every seat is ready.
func (s *Session) MarkReady(playerID string) bool {
	p := s.findPlayer(playerID)
	if !s.Started || s.SetupComplete || p == nil {
		return false
	}
	p.Ready = true
	s.SetupComplete = s.allReady()
	return true
}

func (s *Session) allReady() bool {
	for _, p := range s.Players {
		if !p.Ready {
			return false
		}
	}
	return true
}

func (s *Session) findPlayer(playerID string) *rules.Player {
	for _, p := range s.Players {
		if p.PlayerID == playerID {
			return p
		}
	}
	return nil
}

// CurrentPlayer returns the seat whose turn it is, or nil.
func (s *Session) CurrentPlayer() *rules.Player {
	if s.CurrentIndex < 0 || s.CurrentIndex >= len(s.Players) {
		return nil
	}
	return s.Players[s.CurrentIndex]
}

// CurrentPlayerID returns the id of the player whose turn it is, or "".
func (s *Session) CurrentPlayerID() string {
	if p := s.CurrentPlayer(); p != nil {
		return p.PlayerID
	}
	return ""
}

// PlayCards plays an explicit list of cards from the first non-empty zone
// (hand, then face-up, then face-down).
func (s *Session) PlayCards(cards []rules.Card) PlayResult {
	if s.Finished || !s.SetupComplete {
		return ResultInvalid
	}
	player := s.CurrentPlayer()
	if player == nil {
		return ResultInvalid
	}
	result := s.resolvePlayResult(player, cards)
	if result != ResultSuccess {
		return result
	}
	s.finishSuccessfulPlay(cards[0], player)
	return result
}

// PlaySelections plays the cards named by zone and index. Mixed hand and face-up
// plays are accepted only when the deck is empty and the config allows them.
func (s *Session) PlaySelections(selections []CardSelection) PlayResult {
	if s.Finished || !s.SetupComplete || len(selections) == 0 {
		return ResultInvalid
	}
	player := s.CurrentPlayer()
	if player == nil {
		return ResultInvalid
	}
	selected, sources, ok := s.resolveSelections(player, selections)
	if !ok {
		return ResultInvalid
	}
	var result PlayResult
	switch {
	case sources[SourceFaceDown]:
		result = ResultInvalid
		if len(selections) == 1 && len(sources) == 1 {
			result = s.playFromFaceDown(player, selected)
		}
	case sources[SourceHand] && sources[SourceFaceUp]:
		result = s.playMixedHandAndFaceUp(player, selections, selected)
	case sources[SourceHand]:
		result = s.playFromHand(player, selected)
	default:
		result = s.playFromFaceUp(player, selected)
	}
	if result == ResultSuccess {
		s.finishSuccessfulPlay(selected[0], player)
	}
	return result
}

// PickupPile gives the discard pile to the current player and ends their turn.
func (s *Session) PickupPile() PlayResult {
	if s.Finished || !s.SetupComplete || len(s.Discard) == 0 {
		return ResultInvalid
	}
	player := s.CurrentPlayer()
	if player == nil {
		return ResultInvalid
	}
	player.Hand = append(player.Hand, s.Discard...)
	s.Discard = []rules.Card{}
	sortCards(player.Hand)
	s.nextPlayer()
	return ResultPickup
}

// resolveSelections maps each selection to a card. It rejects unknown zones,
// negative or out-of-range indexes and duplicate selections.
func (s *Session) resolveSelections(player *rules.Player, selections []CardSelection) ([]rules.Card, map[CardSource]bool, bool) {
	seen := make(map[CardSelection]bool, len(selections))
	sources := make(map[CardSource]bool, len(selections))
	selected := make([]rules.Card, 0, len(selections))
	for _, sel := range selections {
		zone, valid := zoneOf(player, sel.Source)
		if !valid || sel.Index < 0 || seen[sel] || sel.Index >= len(zone) {
			return nil, nil, false
		}
		seen[sel] = true
		sources[sel.Source] = true
		selected = append(selected, zone[sel.Index])
	}
	return selected, sources, true
}

func zoneOf(p *rules.Player, source CardSource) ([]rules.Card, bool) {
	switch source {
	case SourceHand:
		return p.Hand, true
	case SourceFaceUp:
		return p.FaceUp, true
	case SourceFaceDown:
		return p.FaceDown, true
	default:
		return nil, false
	}
}

func (s *Session) resolvePlayResult(player *rules.Player, cards []rules.Card) PlayResult {
	switch {
	case len(player.Hand) > 0:
		return s.playFromHand(player, cards)
	case len(player.FaceUp) > 0:
		return s.playFromFaceUp(player, cards)
	case len(player.FaceDown) > 0:
		return s.playFromFaceDown(player, cards)
	default:
		return ResultInvalid
	}
}

func (s *Session) playMixedHandAndFaceUp(player *rules.Player, selections []CardSelection, selected []rules.Card) PlayResult {
	if !s.isValidMixedPlay(selected) {
		return ResultInvalid
	}
	handRemove := map[int]bool{}
	faceUpRemove := map[int]bool{}
	for _, sel := range selections {
		if sel.Source == SourceHand {
			handRemove[sel.Index] = true
		} else {
			faceUpRemove[sel.Index] = true
		}
	}
	player.Hand = dropIndexes(player.Hand, handRemove)
	player.FaceUp = dropIndexes(player.FaceUp, faceUpRemove)
	s.Discard = append(s.Discard, selected...)
	s.postPlayCleanup(player)
	return ResultSuccess
}

func (s *Session) isValidMixedPlay(selected []rules.Card) bool {
	return s.Config.AllowMixedHandAndFaceUpWhenDeckEmpty &&
		s.Deck.Len() == 0 &&
		!s.notAllSameValue(selected) &&
		!s.cannotPlayAll(selected)
}

func (s *Session) playFromHand(player *rules.Player, cards []rules.Card) PlayResult {
	if len(player.Hand) == 0 {
		return ResultInvalid
	}
	idx, ok := matchIndexes(player.Hand, cards)
	if !ok {
		return ResultInvalid
	}
	matched := pick(player.Hand, idx)
	if s.notAllSameValue(matched) || s.cannotPlayAll(matched) {
		return ResultInvalid
	}
	s.Discard = append(s.Discard, matched...)
	player.Hand = dropIndexes(player.Hand, indexSet(idx))
	s.postPlayCleanup(player)
	return ResultSuccess
}

func (s *Session) playFromFaceUp(player *rules.Player, cards []rules.Card) PlayResult {
	if len(player.Hand) > 0 || len(player.FaceUp) == 0 {
		return ResultInvalid
	}
	idx, ok := matchIndexes(player.FaceUp, cards)
	if !ok {
		return ResultInvalid
	}
	matched := pick(player.FaceUp, idx)
	if s.notAllSameValue(matched) || s.cannotPlayAll(matched) {
		return s.failedFaceUpPlay(player, matched, idx)
	}
	s.Discard = append(s.Discard, matched...)
	player.FaceUp = dropIndexes(player.FaceUp, indexSet(idx))
	s.postPlayCleanup(player)
	return ResultSuccess
}

// failedFaceUpPlay handles an illegal face-up play. Without the option the move
// is rejected and nothing changes. With allowFailedFaceUpPlay the cards land on
// the pile and the player picks up the whole pile.
func (s *Session) failedFaceUpPlay(player *rules.Player, matched []rules.Card, idx []int) PlayResult {
	if !s.Config.AllowFailedFaceUpPlay {
		return ResultInvalid
	}
	player.FaceUp = dropIndexes(player.FaceUp, indexSet(idx))
	s.Discard = append(s.Discard, matched...)
	player.Hand = append(player.Hand, s.Discard...)
	s.Discard = []rules.Card{}
	sortCards(player.Hand)
	s.nextPlayer()
	return ResultPickup
}

// playFromFaceDown handles a blind flip. The flipped cards are removed first;
// if they are not playable the player takes them plus the whole pile.
func (s *Session) playFromFaceDown(player *rules.Player, cards []rules.Card) PlayResult {
	if len(player.Hand) > 0 || len(player.FaceUp) > 0 || len(player.FaceDown) == 0 {
		return ResultInvalid
	}
	idx, ok := matchIndexes(player.FaceDown, cards)
	if !ok {
		return ResultInvalid
	}
	matched := pick(player.FaceDown, idx)
	player.FaceDown = dropIndexes(player.FaceDown, indexSet(idx))
	if s.notAllSameValue(matched) || s.cannotPlayAll(matched) {
		player.Hand = append(player.Hand, matched...)
		player.Hand = append(player.Hand, s.Discard...)
		s.Discard = []rules.Card{}
		sortCards(player.Hand)
		s.nextPlayer()
		return ResultPickup
	}
	s.Discard = append(s.Discard, matched...)
	s.postPlayCleanup(player)
	return ResultSuccess
}

// finishSuccessfulPlay applies after-effects and advances the turn unless the
// play grants a replay (a burn, a BURNER card, or a replay-value card).
func (s *Session) finishSuccessfulPlay(card rules.Card, player *rules.Player) {
	burnedByCount := s.lastPlayBurned
	s.lastPlayBurned = false
	s.Discard = rules.AfterEffect(card, s.Discard, player, s.Players)
	burnedByRule := card.Rule == rules.RuleBurner
	if (!burnedByCount && !burnedByRule && !s.Config.CanPlayAgain(card.Value)) || player.Out {
		s.nextPlayer()
	}
	s.checkGameEnd()
}

// postPlayCleanup burns the pile when it reaches the burn count, refills the
// hand from the deck, and marks the player out when no cards remain.
func (s *Session) postPlayCleanup(player *rules.Player) {
	s.lastPlayBurned = rules.ShouldBurn(s.Discard, s.Config.BurnCount)
	if s.lastPlayBurned {
		s.Discard = []rules.Card{}
	}
	for len(player.Hand) < s.Config.HandCount {
		c, ok := s.Deck.Draw()
		if !ok {
			break
		}
		player.Hand = append(player.Hand, c)
	}
	sortCards(player.Hand)
	if len(player.Hand) == 0 && len(player.FaceUp) == 0 && len(player.FaceDown) == 0 {
		player.Out = true
	}
}

func (s *Session) checkGameEnd() {
	active := 0
	var remaining *rules.Player
	for _, p := range s.Players {
		if !p.Out {
			active++
			if remaining == nil {
				remaining = p
			}
		}
	}
	if active <= 1 {
		s.Finished = true
		if remaining != nil {
			s.ShitheadID = remaining.PlayerID
		}
	}
}

// nextPlayer advances to the next seat that still has cards.
func (s *Session) nextPlayer() {
	n := len(s.Players)
	if n == 0 {
		return
	}
	for i := 0; i < n; i++ {
		s.CurrentIndex = (s.CurrentIndex + 1) % n
		if !s.Players[s.CurrentIndex].Out {
			return
		}
	}
}

// notAllSameValue is true when the cards differ in value, or when there are none.
func (s *Session) notAllSameValue(cards []rules.Card) bool {
	if len(cards) == 0 {
		return true
	}
	for _, c := range cards {
		if c.Value != cards[0].Value {
			return true
		}
	}
	return false
}

func (s *Session) cannotPlayAll(cards []rules.Card) bool {
	for _, c := range cards {
		if !rules.CanPlay(c, s.Discard) {
			return true
		}
	}
	return false
}

// matchIndexes finds, for each selected card, an unused equal card in available.
// It returns the indexes in selection order.
func matchIndexes(available, selected []rules.Card) ([]int, bool) {
	if len(selected) == 0 {
		return nil, false
	}
	used := make([]bool, len(available))
	indexes := make([]int, 0, len(selected))
	for _, want := range selected {
		found := -1
		for i, have := range available {
			if !used[i] && sameCard(have, want) {
				found = i
				break
			}
		}
		if found < 0 {
			return nil, false
		}
		used[found] = true
		indexes = append(indexes, found)
	}
	return indexes, true
}

func sameCard(a, b rules.Card) bool {
	return a.Suit == b.Suit && a.Value == b.Value && a.Rule == b.Rule && a.AlwaysPlayable == b.AlwaysPlayable
}

func pick(cards []rules.Card, indexes []int) []rules.Card {
	out := make([]rules.Card, 0, len(indexes))
	for _, i := range indexes {
		out = append(out, cards[i])
	}
	return out
}

func indexSet(indexes []int) map[int]bool {
	set := make(map[int]bool, len(indexes))
	for _, i := range indexes {
		set[i] = true
	}
	return set
}

// dropIndexes returns the cards whose position is not in remove, preserving order.
func dropIndexes(cards []rules.Card, remove map[int]bool) []rules.Card {
	kept := make([]rules.Card, 0, len(cards))
	for i, c := range cards {
		if !remove[i] {
			kept = append(kept, c)
		}
	}
	return kept
}

func sortCards(cards []rules.Card) {
	sort.SliceStable(cards, func(i, j int) bool {
		if cards[i].Value != cards[j].Value {
			return cards[i].Value < cards[j].Value
		}
		return string(cards[i].Suit) < string(cards[j].Suit)
	})
}
