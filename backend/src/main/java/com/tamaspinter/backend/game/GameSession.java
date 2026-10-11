package com.tamaspinter.backend.game;

import com.tamaspinter.backend.bot.BotType;
import com.tamaspinter.backend.entity.GameSessionEntity;
import com.tamaspinter.backend.mapper.SessionMapper;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.CardRule;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.rules.RuleEngine;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;

@Getter
@Setter
@Builder
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CyclomaticComplexity", "PMD.GodClass"})
public class GameSession {
    /** Activity feed keeps only the most recent entries so the persisted item stays small. */
    public static final int MAX_EVENTS = 30;

    /**
     * Why the last play or pickup call returned {@link PlayResult#INVALID}. Not persisted; cleared at the start of
     * every play and pickup call. The not-your-turn check runs in the Lambda before the session is touched.
     */
    public enum InvalidReason {
        SETUP_NOT_COMPLETE,
        GAME_FINISHED,
        EMPTY_SELECTION,
        CARD_NOT_AVAILABLE,
        WRONG_ZONE,
        FACE_DOWN_ONE_AT_A_TIME,
        MIXED_VALUES,
        TOO_LOW,
        TOO_HIGH,
        MIXED_HAND_FACEUP_NOT_ALLOWED,
        PILE_EMPTY
    }

    /** Rating assumed for a player whose rating is unknown. */
    public static final double DEFAULT_RATING = 1000.0;
    /** Most seats a game can have, however many decks it uses. Joins beyond the deck capacity are rejected. */
    public static final int MAX_PLAYERS = 10;
    /** Cards in one standard deck. */
    public static final int DECK_CARD_COUNT = 52;
    /** Player id prefix of bot seats; Cognito subs are plain UUIDs, so ids never collide. */
    public static final String BOT_ID_PREFIX = "bot-";

    private final String sessionId;
    @Builder.Default
    private final List<GameEvent> events = new ArrayList<>();
    @Builder.Default
    private final List<Player> players = new ArrayList<>();
    @Builder.Default
    private final Deque<Card> discardPile = new ArrayDeque<>();
    private Deck deck;
    private int currentIndex;
    @Builder.Default
    private GameConfig config = GameConfig.defaultGameConfig();
    private boolean started;
    @Builder.Default
    private boolean setupComplete = true;
    private boolean lastPlayBurned;
    private boolean finished;
    private String shitheadId;
    private String ownerId;
    private String createdAt;
    private Long ttl;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private InvalidReason lastInvalidReason;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private int lastRequiredPileValue;
    /** Told about every public move; bots that count cards build their memory from it. Not persisted itself. */
    @Builder.Default
    private PublicMoveObserver observer = PublicMoveObserver.NONE;

    /**
     * Seats that the decks hold when every player is dealt a full layout, capped at {@link #MAX_PLAYERS}.
     * With the default 3 + 3 + 3 layout one deck seats 5 players and two decks reach the cap.
     */
    public static int seatCapacity(int decksCount, int faceDownCount, int faceUpCount, int handCount) {
        int cardsPerPlayer = faceDownCount + faceUpCount + handCount;
        if (cardsPerPlayer <= 0) {
            return MAX_PLAYERS;
        }
        return Math.min(MAX_PLAYERS, decksCount * DECK_CARD_COUNT / cardsPerPlayer);
    }

    /** Seats of this game, from its deck count and card layout. */
    public int seatCapacity() {
        return seatCapacity(config.getDecksCount(), config.getFaceDownCount(), config.getFaceUpCount(),
                config.getHandCount());
    }

    public void addPlayer(String id, String name) {
        if (started) {
            throw new IllegalStateException("Game already started");
        }
        int capacity = seatCapacity();
        if (players.size() >= capacity) {
            throw new IllegalStateException(fullMessage(capacity));
        }
        players.add(Player.builder()
                .playerId(id)
                .username(name)
                .build());
    }

    /** Explains a full game. A one-deck game points the owner at the second deck, the only way to seat more. */
    private String fullMessage(int capacity) {
        if (config.getDecksCount() == 1 && capacity < MAX_PLAYERS) {
            return "Game is full: one deck seats " + capacity + " players. The owner can add a second deck.";
        }
        return "Game is full: at most " + capacity + " players can join";
    }

    /**
     * Seats a computer-controlled player before the start, numbered per type ("Beginner Bot 1", ...).
     * Uses the same seat limit as a human join.
     */
    public Player addBot(BotType type) {
        if (started) {
            throw new IllegalStateException("Game already started");
        }
        int capacity = seatCapacity();
        if (players.size() >= capacity) {
            throw new IllegalStateException(fullMessage(capacity));
        }
        Player bot = Player.builder()
                .playerId(BOT_ID_PREFIX + UUID.randomUUID())
                .username(nextBotName(type))
                .botType(type)
                .build();
        players.add(bot);
        return bot;
    }

    private String nextBotName(BotType type) {
        Set<String> taken = new HashSet<>();
        players.forEach(player -> taken.add(player.getUsername()));
        int number = 1;
        while (taken.contains(type.getDisplayName() + " " + number)) {
            number++;
        }
        return type.getDisplayName() + " " + number;
    }

    /** Removes a bot seat before the start. False when the game started or the id is not a bot of this game. */
    public boolean removeBot(String botId) {
        if (started) {
            return false;
        }
        return players.removeIf(player -> player.isBot() && player.getPlayerId().equals(botId));
    }

    /**
     * Removes a player before the start. Ownership passes to the next human; when only bots would remain the
     * lobby is emptied, so callers delete it as they do for an empty lobby.
     */
    public void removePlayer(String playerId) {
        if (started) {
            throw new IllegalStateException("Cannot leave a started game");
        }
        players.removeIf(p -> p.getPlayerId().equals(playerId));
        if (players.stream().noneMatch(player -> !player.isBot())) {
            players.clear();
            return;
        }
        if (ownerId != null && ownerId.equals(playerId)) {
            ownerId = players.stream().filter(player -> !player.isBot()).findFirst().orElseThrow().getPlayerId();
        }
    }

    /** Starts the game with the first player in the list as the starter. */
    public void start() {
        start(Map.of());
    }

    /**
     * Starts the game. The lowest-rated player starts (missing ratings count as {@link #DEFAULT_RATING});
     * among equal ratings the first player in the list starts.
     */
    public void start(Map<String, Double> ratings) {
        int cardsPerPlayer = config.getFaceDownCount() + config.getFaceUpCount() + config.getHandCount();
        int totalCards = config.getDecksCount() * DECK_CARD_COUNT;
        if (cardsPerPlayer > 0 && players.size() * cardsPerPlayer > totalCards) {
            int supported = Math.min(MAX_PLAYERS, totalCards / cardsPerPlayer);
            throw new IllegalStateException("Not enough cards: this setup supports at most " + supported
                    + " players with " + config.getDecksCount() + " deck(s)");
        }
        deck = new Deck(config.getDecksCount(), config);
        for (Player player : players) {
            for (int i = 0; i < config.getFaceDownCount(); i++) {
                player.getFaceDown().add(deck.draw().orElseThrow());
            }
            for (int i = 0; i < config.getFaceUpCount(); i++) {
                player.getFaceUp().add(deck.draw().orElseThrow());
            }
            for (int i = 0; i < config.getHandCount(); i++) {
                player.getHand().add(deck.draw().orElseThrow());
            }
            player.sortHand();
            player.sortFaceUp();
            player.setReady(false);
        }
        currentIndex = lowestRatedIndex(ratings);
        started = true;
        setupComplete = false;
    }

    private int lowestRatedIndex(Map<String, Double> ratings) {
        int lowest = 0;
        double lowestRating = Double.POSITIVE_INFINITY;
        for (int i = 0; i < players.size(); i++) {
            double rating = ratings.getOrDefault(players.get(i).getPlayerId(), DEFAULT_RATING);
            if (rating < lowestRating) {
                lowestRating = rating;
                lowest = i;
            }
        }
        return lowest;
    }

    /**
     * Lets the owner pick who starts while the card setup is still running. Changes only the current player;
     * readiness and the other setup state are left alone.
     */
    public boolean setStarter(String requesterId, String starterId) {
        if (!started || setupComplete || finished || requesterId == null || !requesterId.equals(ownerId)) {
            return false;
        }
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getPlayerId().equals(starterId)) {
                currentIndex = i;
                return true;
            }
        }
        return false;
    }

    public boolean swapStartingCards(String playerId, int handIndex, int faceUpIndex) {
        return swapStartingCards(playerId, List.of(handIndex), List.of(faceUpIndex));
    }

    /**
     * Swaps the i-th selected hand card with the i-th selected face-up card, during setup only.
     * Nothing changes unless both lists are non-empty, of equal size, free of duplicates and in range.
     */
    public boolean swapStartingCards(String playerId, List<Integer> handIndices, List<Integer> faceUpIndices) {
        Player player = findPlayer(playerId);
        if (!started || setupComplete || player == null || player.isReady()
                || handIndices == null || faceUpIndices == null
                || handIndices.isEmpty() || handIndices.size() != faceUpIndices.size()
                || !isValidSelection(handIndices, player.getHand().size())
                || !isValidSelection(faceUpIndices, player.getFaceUp().size())) {
            return false;
        }
        List<Card> hand = new ArrayList<>(player.getHand());
        List<Card> faceUp = new ArrayList<>(player.getFaceUp());
        observer.handCardsPutFaceUp(player, handIndices.stream().map(hand::get).toList());
        observer.faceUpTakenIntoHand(player, faceUpIndices.stream().map(faceUp::get).toList());
        for (int i = 0; i < handIndices.size(); i++) {
            int handIndex = handIndices.get(i);
            int faceUpIndex = faceUpIndices.get(i);
            Card handCard = hand.set(handIndex, faceUp.get(faceUpIndex));
            faceUp.set(faceUpIndex, handCard);
        }
        player.getHand().clear();
        player.getHand().addAll(hand);
        player.getFaceUp().clear();
        player.getFaceUp().addAll(faceUp);
        player.sortHand();
        player.sortFaceUp();
        return true;
    }

    private static boolean isValidSelection(List<Integer> indices, int size) {
        Set<Integer> seen = new HashSet<>();
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= size || !seen.add(index)) {
                return false;
            }
        }
        return true;
    }

    public boolean markReady(String playerId) {
        Player player = findPlayer(playerId);
        if (!started || setupComplete || player == null) {
            return false;
        }
        player.setReady(true);
        recordEvent(GameEventType.READY, player, List.of(), 0);
        setupComplete = players.stream().allMatch(Player::isReady);
        return true;
    }

    private Player findPlayer(String playerId) {
        return players.stream().filter(player -> player.getPlayerId().equals(playerId)).findFirst().orElse(null);
    }

    /** Appends a feed entry, dropping the oldest once the cap is reached. Sequence numbers only ever grow. */
    private void recordEvent(GameEventType type, Player player, List<Card> cards, int count) {
        long seq = events.isEmpty() ? 1L : events.get(events.size() - 1).seq() + 1;
        events.add(new GameEvent(seq, type, player.getPlayerId(), player.getUsername(),
                List.copyOf(cards), count, System.currentTimeMillis()));
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /** Places the played cards on the pile, logs the play, then applies burn and out checks. */
    private void commitPlay(Player player, List<Card> played) {
        played.forEach(discardPile::addLast);
        observer.cardsPlayed(player, List.copyOf(played));
        recordEvent(GameEventType.PLAYED, player, played, played.size());
        postPlayCleanup(player);
    }

    private boolean notAllCardsAreTheSameValue(List<Card> cards) {
        if (cards.isEmpty()) {
            return true;
        }
        int value = cards.get(0).getValue();
        for (Card card : cards) {
            if (card.getValue() != value) {
                return true;
            }
        }
        return false;
    }

    private boolean playerCannotPlayAllSelectedCards(List<Card> cards) {
        for (Card card : cards) {
            if (!RuleEngine.canPlay(card, discardPile)) {
                return true;
            }
        }
        return false;
    }

    /** The reason the cards break the same-value or pile rules, or null when they are legal. */
    private InvalidReason ruleViolation(List<Card> cards) {
        if (notAllCardsAreTheSameValue(cards)) {
            return InvalidReason.MIXED_VALUES;
        }
        return playerCannotPlayAllSelectedCards(cards) ? pileRejectionReason() : null;
    }

    /**
     * Explains a rejected card: the pile top decides the direction. A 'smaller' top (looking through transparent
     * cards) needs an equal or lower card, every other top needs an equal or higher one.
     */
    private InvalidReason pileRejectionReason() {
        Iterator<Card> fromTop = discardPile.descendingIterator();
        while (fromTop.hasNext()) {
            Card top = fromTop.next();
            if (top.getRule() != CardRule.TRANSPARENT) {
                lastRequiredPileValue = top.getValue();
                return top.getRule() == CardRule.SMALLER ? InvalidReason.TOO_HIGH : InvalidReason.TOO_LOW;
            }
        }
        return InvalidReason.TOO_LOW;
    }

    private void clearInvalidReason() {
        lastInvalidReason = null;
        lastRequiredPileValue = 0;
    }

    private PlayResult invalid(InvalidReason reason) {
        lastInvalidReason = reason;
        return PlayResult.INVALID;
    }

    /** Why the last play or pickup returned INVALID, or null if it did not. */
    public InvalidReason getLastInvalidReason() {
        return lastInvalidReason;
    }

    /** For TOO_LOW / TOO_HIGH: the value of the pile top the card was compared against, otherwise 0. */
    public int getLastRequiredPileValue() {
        return lastRequiredPileValue;
    }

    private Optional<List<Card>> matchSelectedCards(List<Card> available, List<Card> selected) {
        if (selected == null || selected.isEmpty()) {
            return Optional.empty();
        }
        List<Card> unmatched = new ArrayList<>(available);
        List<Card> matched = new ArrayList<>();
        for (Card selectedCard : selected) {
            int match = -1;
            for (int i = 0; i < unmatched.size(); i++) {
                if (sameCard(unmatched.get(i), selectedCard)) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                return Optional.empty();
            }
            matched.add(unmatched.remove(match));
        }
        return Optional.of(matched);
    }

    private boolean sameCard(Card left, Card right) {
        return left != null && right != null
                && left.getSuit() == right.getSuit()
                && left.getValue() == right.getValue()
                && left.getRule() == right.getRule()
                && left.isAlwaysPlayable() == right.isAlwaysPlayable();
    }

    public PlayResult playCards(List<Card> cards) {
        clearInvalidReason();
        if (finished) {
            return invalid(InvalidReason.GAME_FINISHED);
        }
        if (!setupComplete) {
            return invalid(InvalidReason.SETUP_NOT_COMPLETE);
        }
        if (cards == null || cards.isEmpty()) {
            return invalid(InvalidReason.EMPTY_SELECTION);
        }
        Player player = players.get(currentIndex);
        PlayResult result = resolvePlayResult(player, cards);

        if (result != PlayResult.SUCCESS) {
            return result;
        }
        finishSuccessfulPlay(cards.get(0), player);
        return result;
    }

    public PlayResult playSelections(List<CardSelection> selections) {
        clearInvalidReason();
        if (finished) {
            return invalid(InvalidReason.GAME_FINISHED);
        }
        if (!setupComplete) {
            return invalid(InvalidReason.SETUP_NOT_COMPLETE);
        }
        if (selections == null || selections.isEmpty()) {
            return invalid(InvalidReason.EMPTY_SELECTION);
        }
        Player player = players.get(currentIndex);
        ResolvedSelections resolved = resolveSelections(player, selections);
        if (resolved == null) {
            return invalid(InvalidReason.CARD_NOT_AVAILABLE);
        }
        PlayResult result;
        if (resolved.sources().contains(CardSource.FACE_DOWN)) {
            result = selectionsAreSingleFaceDownFlip(selections, resolved)
                    ? playFromFaceDown(resolved.cards())
                    : invalid(faceDownSelectionReason(resolved));
        } else if (isMixedHandAndFaceUp(resolved.sources())) {
            result = playMixedHandAndFaceUp(player, selections, resolved.cards());
        } else {
            result = resolved.sources().contains(CardSource.HAND)
                    ? playFromHand(resolved.cards())
                    : playFromFaceUp(resolved.cards());
        }
        if (result == PlayResult.SUCCESS) {
            finishSuccessfulPlay(resolved.cards().get(0), player);
        }
        return result;
    }

    private static boolean selectionsAreSingleFaceDownFlip(List<CardSelection> selections, ResolvedSelections resolved) {
        return selections.size() == 1 && resolved.sources().size() == 1;
    }

    /** Several face-down cards chosen at once is its own case; mixing face-down with other zones is a wrong zone. */
    private static InvalidReason faceDownSelectionReason(ResolvedSelections resolved) {
        return resolved.sources().size() == 1 ? InvalidReason.FACE_DOWN_ONE_AT_A_TIME : InvalidReason.WRONG_ZONE;
    }

    private ResolvedSelections resolveSelections(Player player, List<CardSelection> selections) {
        List<Card> hand = new ArrayList<>(player.getHand());
        List<Card> faceUp = new ArrayList<>(player.getFaceUp());
        List<Card> faceDown = new ArrayList<>(player.getFaceDown());
        List<Card> selectedCards = new ArrayList<>();
        Set<CardSelection> seen = new HashSet<>();
        Set<CardSource> sources = EnumSet.noneOf(CardSource.class);
        for (CardSelection selection : selections) {
            if (selection == null || selection.source() == null || selection.index() < 0
                    || !seen.add(selection)) {
                return null;
            }
            sources.add(selection.source());
            List<Card> available = cardsForSource(selection.source(), hand, faceUp, faceDown);
            if (selection.index() >= available.size()) {
                return null;
            }
            selectedCards.add(available.get(selection.index()));
        }
        return new ResolvedSelections(sources, selectedCards);
    }

    private boolean isMixedHandAndFaceUp(Set<CardSource> sources) {
        return sources.contains(CardSource.HAND) && sources.contains(CardSource.FACE_UP);
    }

    private record ResolvedSelections(Set<CardSource> sources, List<Card> cards) { }

    private List<Card> cardsForSource(
            CardSource source, List<Card> hand, List<Card> faceUp, List<Card> faceDown) {
        return switch (source) {
            case HAND -> hand;
            case FACE_UP -> faceUp;
            case FACE_DOWN -> faceDown;
        };
    }

    private PlayResult playMixedHandAndFaceUp(
            Player player, List<CardSelection> selections, List<Card> selectedCards) {
        InvalidReason violation = mixedPlayViolation(selectedCards);
        if (violation != null) {
            return invalid(violation);
        }
        removeMixedSelections(player, selections, selectedCards);
        commitPlay(player, selectedCards);
        return PlayResult.SUCCESS;
    }

    /** Mixing hand and face-up cards is only allowed with the option on and the draw pile empty. */
    private InvalidReason mixedPlayViolation(List<Card> selectedCards) {
        if (!mixedPlayAllowed()) {
            return InvalidReason.MIXED_HAND_FACEUP_NOT_ALLOWED;
        }
        return ruleViolation(selectedCards);
    }

    private boolean mixedPlayAllowed() {
        return config.isAllowMixedHandAndFaceUpWhenDeckEmpty() && deck != null && deck.getCards().isEmpty();
    }

    private void removeMixedSelections(Player player, List<CardSelection> selections, List<Card> selectedCards) {
        for (int i = 0; i < selections.size(); i++) {
            CardSelection selection = selections.get(i);
            if (selection.source() == CardSource.HAND) {
                player.getHand().remove(selectedCards.get(i));
            } else {
                player.getFaceUp().remove(selectedCards.get(i));
            }
        }
    }

    private void finishSuccessfulPlay(Card card, Player player) {
        boolean playsAgain = grantsAnotherTurn(card, player);
        applyAfterEffect(card, player);
        if (playsAgain) {
            recordEvent(GameEventType.PLAYED_AGAIN, player, List.of(), 0);
        } else {
            nextPlayer();
        }
        checkGameEnd();
    }

    /** Also consumes the pile-burn flag set by postPlayCleanup, so it must run exactly once per successful play. */
    private boolean grantsAnotherTurn(Card card, Player player) {
        boolean burnedByCount = lastPlayBurned;
        lastPlayBurned = false;
        boolean burnedByRule = card.getRule() == CardRule.BURNER;
        return (burnedByCount || burnedByRule || config.canPlayAgain(card.getValue())) && !player.isOut();
    }

    private void applyAfterEffect(Card card, Player player) {
        int pileBeforeRule = discardPile.size();
        if (card.getRule() == CardRule.BURNER && pileBeforeRule > 0) {
            observer.pileBurned(List.copyOf(discardPile));
        }
        RuleEngine.playAfterEffect(card, discardPile, player, players);
        if (card.getRule() == CardRule.BURNER && pileBeforeRule > 0) {
            recordEvent(GameEventType.BURNED, player, List.of(), pileBeforeRule);
        }
        if (card.getRule() == CardRule.REVERSE) {
            recordEvent(GameEventType.REVERSED, player, List.of(), 0);
        }
    }

    private PlayResult resolvePlayResult(Player player, List<Card> cards) {
        if (!player.getHand().isEmpty()) {
            return playFromHand(cards);
        }
        if (!player.getFaceUp().isEmpty()) {
            return playFromFaceUp(cards);
        }
        if (!player.getFaceDown().isEmpty()) {
            return playFromFaceDown(cards);
        }
        return invalid(InvalidReason.CARD_NOT_AVAILABLE);
    }

    public PlayResult pickupPile() {
        clearInvalidReason();
        if (finished) {
            return invalid(InvalidReason.GAME_FINISHED);
        }
        if (!setupComplete) {
            return invalid(InvalidReason.SETUP_NOT_COMPLETE);
        }
        if (discardPile.isEmpty()) {
            return invalid(InvalidReason.PILE_EMPTY);
        }
        return pickUpPile(players.get(currentIndex), List.of(), GameEventType.PICKED_UP);
    }

    /**
     * Moves the pile and any failed cards into the player's hand, logs the pickup, and passes the turn.
     * The failed cards are the ones revealed by a blind flip or an illegal face-up play.
     */
    private PlayResult pickUpPile(Player player, List<Card> failedCards, GameEventType type) {
        List<Card> taken = new ArrayList<>(failedCards);
        taken.addAll(discardPile);
        observer.cardsPickedUp(player, List.copyOf(taken));
        int count = taken.size();
        failedCards.forEach(player.getHand()::addLast);
        discardPile.forEach(player.getHand()::addLast);
        recordEvent(type, player, failedCards, count);
        discardPile.clear();
        player.sortHand();
        nextPlayer();
        return PlayResult.PICKUP;
    }

    private PlayResult playFromHand(List<Card> cards) {
        Player player = players.get(currentIndex);
        if (player.getHand().isEmpty()) {
            return invalid(InvalidReason.CARD_NOT_AVAILABLE);
        }
        Optional<List<Card>> matchedCards = matchSelectedCards(new ArrayList<>(player.getHand()), cards);
        if (matchedCards.isEmpty()) {
            return invalid(InvalidReason.CARD_NOT_AVAILABLE);
        }
        List<Card> matched = matchedCards.get();
        InvalidReason violation = ruleViolation(matched);
        if (violation != null) {
            return invalid(violation);
        }
        matched.forEach(player.getHand()::remove);
        commitPlay(player, matched);
        return PlayResult.SUCCESS;
    }

    private PlayResult playFromFaceUp(List<Card> cards) {
        Player player = players.get(currentIndex);
        if (!player.getHand().isEmpty()) {
            return invalid(InvalidReason.WRONG_ZONE);
        }
        if (player.getFaceUp().isEmpty()) {
            return invalid(InvalidReason.CARD_NOT_AVAILABLE);
        }
        Optional<List<Card>> matchedCards = matchSelectedCards(new ArrayList<>(player.getFaceUp()), cards);
        if (matchedCards.isEmpty()) {
            return invalid(InvalidReason.CARD_NOT_AVAILABLE);
        }
        List<Card> matched = matchedCards.get();
        InvalidReason violation = ruleViolation(matched);
        if (violation != null) {
            return config.isAllowFailedFaceUpPlay()
                    ? pickUpAfterFailedFaceUpPlay(player, matched)
                    : invalid(violation);
        }
        matched.forEach(player.getFaceUp()::remove);
        commitPlay(player, matched);
        return PlayResult.SUCCESS;
    }

    /**
     * Rule for an illegal face-up play with an empty hand: the selected cards are placed on the pile and the
     * player immediately picks up the whole pile, including those cards. Mirrors a failed blind flip.
     */
    private PlayResult pickUpAfterFailedFaceUpPlay(Player player, List<Card> matched) {
        matched.forEach(player.getFaceUp()::remove);
        return pickUpPile(player, matched, GameEventType.FAILED_PLAY);
    }

    private PlayResult playFromFaceDown(List<Card> cards) {
        Player player = players.get(currentIndex);
        if (!player.getHand().isEmpty() || !player.getFaceUp().isEmpty()) {
            return invalid(InvalidReason.WRONG_ZONE);
        }
        if (player.getFaceDown().isEmpty()) {
            return invalid(InvalidReason.CARD_NOT_AVAILABLE);
        }
        Optional<List<Card>> matchedCards = matchSelectedCards(new ArrayList<>(player.getFaceDown()), cards);
        if (matchedCards.isEmpty()) {
            return invalid(InvalidReason.CARD_NOT_AVAILABLE);
        }
        List<Card> matched = matchedCards.get();
        matched.forEach(player.getFaceDown()::remove);
        if (ruleViolation(matched) != null) {
            return pickUpPile(player, matched, GameEventType.FAILED_FLIP);
        }
        commitPlay(player, matched);
        return PlayResult.SUCCESS;
    }

    private void postPlayCleanup(Player player) {
        lastPlayBurned = RuleEngine.shouldBurn(discardPile, config.getBurnCount());
        if (lastPlayBurned) {
            observer.pileBurned(List.copyOf(discardPile));
            recordEvent(GameEventType.BURNED, player, List.of(), discardPile.size());
            discardPile.clear();
        }
        while (player.getHand().size() < config.getHandCount()) {
            Optional<Card> drawn = deck.draw();
            if (drawn.isEmpty()) {
                break;
            }
            player.getHand().addLast(drawn.get());
        }
        player.sortHand();
        if (player.getHand().isEmpty() && player.getFaceUp().isEmpty() && player.getFaceDown().isEmpty()) {
            player.setOut(true);
            recordEvent(GameEventType.OUT, player, List.of(), 0);
        }
    }

    private void checkGameEnd() {
        long activePlayers = players.stream().filter(pl -> !pl.isOut()).count();
        if (activePlayers <= 1) {
            finished = true;
            Optional<Player> remainingPlayer = players.stream()
                    .filter(pl -> !pl.isOut())
                    .findFirst();
            if (remainingPlayer.isPresent()) {
                Player remaining = remainingPlayer.get();
                shitheadId = remaining.getPlayerId();
                recordEvent(GameEventType.FINISHED, remaining, List.of(), 0);
            }
        }
    }

    private void nextPlayer() {
        do {
            currentIndex = (currentIndex + 1) % players.size();
        }
        while (players.get(currentIndex).isOut());
    }

    /** The player whose turn it is, or null when there is none. */
    public Player getCurrentPlayer() {
        if (players.isEmpty() || currentIndex < 0 || currentIndex >= players.size()) {
            return null;
        }
        return players.get(currentIndex);
    }

    /**
     * Every play the current player may make right now, judged by the same zone, same-value and pile checks as
     * {@link #playSelections}. Each entry is a selection list ready for {@code playSelections}. Same-value cards
     * are offered as 1..n of the lowest indexes. Face-down cards are offered one at a time (a blind flip is always
     * allowed; it may end in a pickup). Picking up the pile is not listed: it is allowed whenever the pile is not
     * empty. Empty while the game is finished or in setup.
     */
    public List<List<CardSelection>> legalPlays() {
        Player player = getCurrentPlayer();
        if (finished || !setupComplete || player == null) {
            return List.of();
        }
        List<List<CardSelection>> plays = new ArrayList<>();
        if (player.getHand().isEmpty() && player.getFaceUp().isEmpty()) {
            IntStream.range(0, player.getFaceDown().size())
                    .forEach(index -> plays.add(List.of(new CardSelection(CardSource.FACE_DOWN, index))));
            return plays;
        }
        CardSource zone = player.getHand().isEmpty() ? CardSource.FACE_UP : CardSource.HAND;
        List<Card> cards = new ArrayList<>(zone == CardSource.HAND ? player.getHand() : player.getFaceUp());
        indexesByValue(cards).values().stream()
                .filter(indexes -> !playerCannotPlayAllSelectedCards(List.of(cards.get(indexes.get(0)))))
                .forEach(indexes -> IntStream.rangeClosed(1, indexes.size())
                        .forEach(count -> plays.add(selectionsOf(zone, indexes.subList(0, count)))));
        if (zone == CardSource.HAND && mixedPlayAllowed()) {
            addMixedPlays(plays, cards, new ArrayList<>(player.getFaceUp()));
        }
        return plays;
    }

    private static Map<Integer, List<Integer>> indexesByValue(List<Card> cards) {
        Map<Integer, List<Integer>> indexes = new TreeMap<>();
        IntStream.range(0, cards.size())
                .forEach(index -> indexes.computeIfAbsent(cards.get(index).getValue(), value -> new ArrayList<>()).add(index));
        return indexes;
    }

    private static List<CardSelection> selectionsOf(CardSource source, List<Integer> indexes) {
        return indexes.stream().map(index -> new CardSelection(source, index)).toList();
    }

    /** With the option on and the draw pile empty: every hand and face-up card of one playable value together. */
    private void addMixedPlays(List<List<CardSelection>> plays, List<Card> hand, List<Card> faceUp) {
        Map<Integer, List<Integer>> faceUpByValue = indexesByValue(faceUp);
        indexesByValue(hand).forEach((value, handIndexes) -> {
            List<Integer> faceUpIndexes = faceUpByValue.get(value);
            if (faceUpIndexes == null || playerCannotPlayAllSelectedCards(List.of(hand.get(handIndexes.get(0))))) {
                return;
            }
            List<CardSelection> selection = new ArrayList<>(selectionsOf(CardSource.HAND, handIndexes));
            selection.addAll(selectionsOf(CardSource.FACE_UP, faceUpIndexes));
            plays.add(List.copyOf(selection));
        });
    }

    /**
     * Ends a game that can no longer progress on its own: only bots are left and they hit the move cap of one
     * invocation. The still-active player holding the most cards becomes the shithead. Does nothing while
     * a human is still playing or the game is over.
     */
    public void finishStalledBotGame() {
        if (finished || players.stream().anyMatch(player -> !player.isOut() && !player.isBot())) {
            return;
        }
        players.stream()
                .filter(player -> !player.isOut())
                .max(Comparator.comparingInt(player -> player.getHand().size() + player.getFaceUp().size()
                        + player.getFaceDown().size()))
                .ifPresent(loser -> {
                    shitheadId = loser.getPlayerId();
                    recordEvent(GameEventType.FINISHED, loser, List.of(), 0);
                });
        finished = true;
    }

    public String getCurrentPlayerId() {
        if (players.isEmpty() || currentIndex < 0 || currentIndex >= players.size()) {
            return null;
        }
        return players.get(currentIndex).getPlayerId();
    }

    public GameSessionEntity toEntity() {
        return SessionMapper.toEntity(this);
    }

    @Override
    public String toString() {
        return "GameSession{"
                + "sessionId='" + sessionId + '\''
                + ", number of players=" + players.size()
                + ", discardPile=" + discardPile
                + ", deck=" + deck
                + ", currentIndex=" + currentIndex
                + ", config=" + config
                + ", started=" + started
                + '}';
    }
}

