// Package elo computes rating changes after a finished game.
package elo

import "math"

// KFactor is the maximum rating change per game.
const KFactor = 32.0

// UpdateRatings returns new ratings for every player in current. scores holds
// the actual result per player (1 = survived, 0 = shithead). Players missing
// from scores count as 0. Expected score is averaged over every opponent.
func UpdateRatings(current map[string]float64, scores map[string]float64) map[string]float64 {
	updated := make(map[string]float64, len(current))
	for id, rating := range current {
		expected := expectedAgainstOthers(id, rating, current)
		updated[id] = rating + KFactor*(scores[id]-expected)
	}
	return updated
}

func expectedAgainstOthers(id string, rating float64, all map[string]float64) float64 {
	if len(all) < 2 {
		return 0
	}
	sum := 0.0
	for opponentID, opponentRating := range all {
		if opponentID == id {
			continue
		}
		sum += 1 / (1 + math.Pow(10, (opponentRating-rating)/400))
	}
	return sum / float64(len(all)-1)
}
