package store

import (
	"strconv"

	"github.com/pintertamas/shithead-backend/backend-go/internal/game"
	"github.com/pintertamas/shithead-backend/backend-go/internal/rules"
)

// ToRecord converts a session into the stored form. Fields that the session does
// not own (created_at, ttl, starting, eloUpdated) are carried over from prev so
// that every save preserves them.
func ToRecord(s *game.Session, prev GameRecord) GameRecord {
	record := prev
	record.GameID = s.ID
	record.OwnerID = s.OwnerID
	record.Players = playersToRecords(s.Players)
	record.DiscardPile = nonNil(s.Discard)
	record.Deck = nonNil(s.Deck.Cards())
	record.CurrentPlayerID = s.CurrentPlayerID()
	record.Started = s.Started
	record.SetupComplete = s.SetupComplete
	record.Finished = s.Finished
	record.ShitheadID = s.ShitheadID
	record.Config = configToRecord(s.Config)
	return record
}

// FromRecord rebuilds a session from its stored form. The current seat is found
// by matching CurrentPlayerID, defaulting to the first seat.
func FromRecord(r GameRecord) *game.Session {
	cfg := configFromRecord(r.Config)
	s := &game.Session{
		ID:            r.GameID,
		OwnerID:       r.OwnerID,
		Config:        cfg,
		Started:       r.Started,
		SetupComplete: r.SetupComplete,
		Finished:      r.Finished,
		ShitheadID:    r.ShitheadID,
		Discard:       append([]rules.Card{}, r.DiscardPile...),
		Deck:          game.NewDeck(r.Deck),
	}
	s.Players = make([]*rules.Player, 0, len(r.Players))
	for _, p := range r.Players {
		s.Players = append(s.Players, playerFromRecord(p))
	}
	for i, p := range s.Players {
		if p.PlayerID == r.CurrentPlayerID {
			s.CurrentIndex = i
			break
		}
	}
	return s
}

func playersToRecords(players []*rules.Player) []PlayerRecord {
	out := make([]PlayerRecord, 0, len(players))
	for _, p := range players {
		ready := p.Ready
		out = append(out, PlayerRecord{
			PlayerID: p.PlayerID,
			Username: p.Username,
			Hand:     nonNil(p.Hand),
			FaceUp:   nonNil(p.FaceUp),
			FaceDown: nonNil(p.FaceDown),
			Out:      p.Out,
			Ready:    &ready,
		})
	}
	return out
}

func playerFromRecord(r PlayerRecord) *rules.Player {
	ready := true
	if r.Ready != nil {
		ready = *r.Ready
	}
	return &rules.Player{
		PlayerID: r.PlayerID,
		Username: r.Username,
		Hand:     append([]rules.Card{}, r.Hand...),
		FaceUp:   append([]rules.Card{}, r.FaceUp...),
		FaceDown: append([]rules.Card{}, r.FaceDown...),
		Out:      r.Out,
		Ready:    ready,
	}
}

func configToRecord(c game.GameConfig) *ConfigRecord {
	cardRules := make(map[string]string, len(c.RulesByValue))
	for value, rule := range c.RulesByValue {
		cardRules[strconv.Itoa(value)] = string(rule)
	}
	return &ConfigRecord{
		DecksCount:     c.DecksCount,
		BurnCount:      c.BurnCount,
		FaceDownCount:  c.FaceDownCount,
		FaceUpCount:    c.FaceUpCount,
		HandCount:      c.HandCount,
		AllowMixed:     c.AllowMixedHandAndFaceUpWhenDeckEmpty,
		AllowFailed:    c.AllowFailedFaceUpPlay,
		CardRules:      cardRules,
		AlwaysPlayable: trueKeys(c.AlwaysPlayableByValue),
		CanPlayAgain:   trueKeys(c.PlayAgainByValue),
	}
}

// configFromRecord restores a config. A missing config falls back to the default.
func configFromRecord(r *ConfigRecord) game.GameConfig {
	if r == nil {
		return game.DefaultConfig()
	}
	cfg := game.GameConfig{
		DecksCount:                           r.DecksCount,
		BurnCount:                            r.BurnCount,
		FaceDownCount:                        r.FaceDownCount,
		FaceUpCount:                          r.FaceUpCount,
		HandCount:                            r.HandCount,
		AllowMixedHandAndFaceUpWhenDeckEmpty: r.AllowMixed,
		AllowFailedFaceUpPlay:                r.AllowFailed,
		RulesByValue:                         map[int]rules.CardRule{},
		AlwaysPlayableByValue:                map[int]bool{},
		PlayAgainByValue:                     map[int]bool{},
	}
	for key, name := range r.CardRules {
		value, err := strconv.Atoi(key)
		if err != nil || !rules.KnownRules[rules.CardRule(name)] {
			continue
		}
		cfg.RulesByValue[value] = rules.CardRule(name)
	}
	for _, value := range r.AlwaysPlayable {
		cfg.AlwaysPlayableByValue[value] = true
	}
	for _, value := range r.CanPlayAgain {
		cfg.PlayAgainByValue[value] = true
	}
	return cfg
}

func trueKeys(set map[int]bool) []int {
	keys := make([]int, 0, len(set))
	for value, on := range set {
		if on {
			keys = append(keys, value)
		}
	}
	return keys
}

func nonNil(cards []rules.Card) []rules.Card {
	if cards == nil {
		return []rules.Card{}
	}
	return cards
}
