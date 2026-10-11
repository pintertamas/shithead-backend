package com.tamaspinter.backend.bot;

import com.tamaspinter.backend.game.GameConfig;
import com.tamaspinter.backend.game.GameSession;
import com.tamaspinter.backend.model.Card;
import com.tamaspinter.backend.model.Deck;
import com.tamaspinter.backend.model.Player;
import com.tamaspinter.backend.model.Suit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Card, seat and deal helpers shared by the bot tests. */
final class BotTables {

    private BotTables() {
    }

    static Card card(GameConfig config, int value, Suit suit) {
        return Card.builder()
                .suit(suit)
                .value(value)
                .rule(config.getCardRule(value))
                .alwaysPlayable(config.isAlwaysPlayable(value))
                .build();
    }

    static Card card(GameConfig config, int value) {
        return card(config, value, Suit.HEARTS);
    }

    static Player human(String id) {
        return Player.builder().playerId(id).username(id).build();
    }

    static Player bot(String id) {
        return Player.builder().playerId(id).username(id).botType(BotType.BEGINNER).build();
    }

    /** A running game (setup done, empty draw pile) with the given seats; the first seat is to move. */
    static GameSession running(GameConfig config, Player... seats) {
        GameSession session = GameSession.builder().sessionId("table").config(config).build();
        session.getPlayers().addAll(List.of(seats));
        session.setStarted(true);
        session.setSetupComplete(true);
        session.setDeck(new Deck(List.of()));
        return session;
    }

    static void give(GameSession session, Player player, int... values) {
        for (int value : values) {
            player.getHand().add(card(session.getConfig(), value));
        }
        player.sortHand();
    }

    static void pile(GameSession session, int... values) {
        for (int value : values) {
            session.getDiscardPile().addLast(card(session.getConfig(), value, Suit.DIAMONDS));
        }
    }

    /** Every card of the configured decks, shuffled with a fixed seed. */
    static Deck shuffledDeck(GameConfig config, long seed) {
        List<Card> cards = new ArrayList<>();
        for (int d = 0; d < config.getDecksCount(); d++) {
            for (Suit suit : Suit.values()) {
                for (int value = 2; value <= 14; value++) {
                    cards.add(card(config, value, suit));
                }
            }
        }
        Collections.shuffle(cards, new Random(seed));
        return new Deck(cards);
    }

    /**
     * Starts the game through {@link GameSession#start()} (checks, flags, starter), then replaces the random deal
     * with one from a seeded deck so the game is reproducible.
     */
    static void startDeterministic(GameSession session, long seed) {
        session.start();
        GameConfig config = session.getConfig();
        Deck deck = shuffledDeck(config, seed);
        for (Player player : session.getPlayers()) {
            player.getHand().clear();
            player.getFaceUp().clear();
            player.getFaceDown().clear();
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
        }
        session.setDeck(deck);
    }

    static int cardCount(Player player) {
        return player.getHand().size() + player.getFaceUp().size() + player.getFaceDown().size();
    }

    static long activeCount(GameSession session) {
        return session.getPlayers().stream().filter(player -> !player.isOut()).count();
    }
}
