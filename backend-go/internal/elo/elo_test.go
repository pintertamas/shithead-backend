package elo

import (
	"math"
	"testing"
)

func approx(t *testing.T, name string, got, want float64) {
	t.Helper()
	if math.Abs(got-want) > 0.001 {
		t.Fatalf("%s: got %.4f, want %.4f", name, got, want)
	}
}

func TestUpdateRatings_evenRating_winnerGains16LoserLoses16(t *testing.T) {
	// Given both players at 1000 (expected 0.5 each)
	current := map[string]float64{"winner": 1000, "loser": 1000}
	scores := map[string]float64{"winner": 1, "loser": 0}

	// When
	updated := UpdateRatings(current, scores)

	// Then
	approx(t, "winner", updated["winner"], 1016)
	approx(t, "loser", updated["loser"], 984)
}

func TestUpdateRatings_unevenRating_favouriteGainsLess(t *testing.T) {
	// Given a 1200 favourite beats an 800 underdog
	current := map[string]float64{"winner": 1200, "loser": 800}
	scores := map[string]float64{"winner": 1, "loser": 0}

	// When
	updated := UpdateRatings(current, scores)

	// Then the change is 32/11 for both sides
	approx(t, "winner", updated["winner"], 1200+32.0/11.0)
	approx(t, "loser", updated["loser"], 800-32.0/11.0)
	if updated["winner"]-1200 >= 16 {
		t.Fatalf("favourite should gain less than an even-match 16 points")
	}
}

func TestUpdateRatings_threePlayers_shitheadLoses(t *testing.T) {
	// Given three equal players and one shithead
	current := map[string]float64{"alice": 1000, "bob": 1000, "shithead": 1000}
	scores := map[string]float64{"alice": 1, "bob": 1, "shithead": 0}

	// When
	updated := UpdateRatings(current, scores)

	// Then
	approx(t, "alice", updated["alice"], 1016)
	approx(t, "bob", updated["bob"], 1016)
	approx(t, "shithead", updated["shithead"], 984)
}

func TestUpdateRatings_missingScore_countsAsZero(t *testing.T) {
	current := map[string]float64{"a": 1000, "b": 1000}

	updated := UpdateRatings(current, map[string]float64{"a": 1})

	approx(t, "a", updated["a"], 1016)
	approx(t, "b", updated["b"], 984)
}

func TestUpdateRatings_singlePlayer_hasNoOpponents(t *testing.T) {
	updated := UpdateRatings(map[string]float64{"solo": 1000}, map[string]float64{"solo": 1})

	approx(t, "solo", updated["solo"], 1000+32)
}
