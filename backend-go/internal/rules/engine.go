package rules

// CanPlay reports whether newCard may be played onto pile (the discard pile,
// oldest card first). The rule of the top card decides, except that empty
// piles and always-playable cards are always accepted.
func CanPlay(newCard Card, pile []Card) bool {
	if len(pile) == 0 || newCard.AlwaysPlayable {
		return true
	}
	return canPlayByRule(pile[len(pile)-1].Rule, newCard, pile)
}

// canPlayByRule applies the strategy of the given top-card rule.
func canPlayByRule(rule CardRule, newCard Card, pile []Card) bool {
	switch rule {
	case RuleJoker, RuleBurner:
		return true
	case RuleSmaller:
		return smaller(newCard, pile)
	case RuleTransparent:
		return transparent(newCard, pile)
	default:
		return atLeastTop(newCard, pile)
	}
}

// atLeastTop is the standard rule: the new card must be >= the top card.
func atLeastTop(newCard Card, pile []Card) bool {
	if newCard.AlwaysPlayable || len(pile) == 0 {
		return true
	}
	return newCard.Value >= pile[len(pile)-1].Value
}

// smaller is the SMALLER rule: the new card must be <= the top card.
func smaller(newCard Card, pile []Card) bool {
	if newCard.AlwaysPlayable || len(pile) == 0 {
		return true
	}
	return newCard.Value <= pile[len(pile)-1].Value
}

// transparent skips TRANSPARENT cards and applies the rule of the first
// non-transparent card underneath them.
func transparent(newCard Card, pile []Card) bool {
	if newCard.AlwaysPlayable {
		return true
	}
	effective := pile
	for len(effective) > 0 {
		top := effective[len(effective)-1]
		if top.Rule != RuleTransparent {
			return canPlayByRule(top.Rule, newCard, effective)
		}
		effective = effective[:len(effective)-1]
	}
	return true
}

// ShouldBurn reports whether the last n cards of the pile share the top value.
func ShouldBurn(pile []Card, n int) bool {
	if len(pile) == 0 {
		return false
	}
	topValue := pile[len(pile)-1].Value
	count := 0
	for _, c := range pile[max(0, len(pile)-n):] {
		if c.Value == topValue {
			count++
		}
	}
	return count >= n
}

// AfterEffect applies the post-play effect of the card's rule. A BURNER clears
// the pile (the returned slice is empty); a REVERSE reverses the seating order
// in place, anchored on current. Other rules leave the pile unchanged.
func AfterEffect(card Card, pile []Card, current *Player, players []*Player) []Card {
	switch card.Rule {
	case RuleBurner:
		return []Card{}
	case RuleReverse:
		ReversePlayers(players, current)
	}
	return pile
}

// ReversePlayers reverses the seating order in place, keeping current at its
// index. Turn order then continues to the previous seat.
func ReversePlayers(players []*Player, current *Player) {
	n := len(players)
	currentIndex := -1
	for i, p := range players {
		if p == current {
			currentIndex = i
			break
		}
	}
	if currentIndex < 0 || n < 2 {
		return
	}
	original := append([]*Player(nil), players...)
	for offset := 0; offset < n; offset++ {
		dest := (currentIndex + offset) % n
		src := ((currentIndex-offset)%n + n) % n
		players[dest] = original[src]
	}
}
