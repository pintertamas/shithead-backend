import { useEffect, useRef } from "react";
import "../styles/rules.css";
import { CardRule } from "../config/gameConfig";
import Icon from "./Icon";

export type SpecialCardRule = {
  value: number;
  rule: Exclude<CardRule, "DEFAULT">;
};

type Props = {
  specialRules: SpecialCardRule[];
  faceUpFailurePickup: boolean;
  onClose: () => void;
};

const RULE_TEXT: Record<Exclude<CardRule, "DEFAULT">, { name: string; text: string }> = {
  JOKER: { name: "Joker", text: "Playable on any card, whatever is on the pile." },
  SMALLER: { name: "Smaller", text: "The next card played must be equal or lower." },
  TRANSPARENT: { name: "Transparent", text: "See-through: playable on anything, and the card beneath it still decides what can follow." },
  REVERSE: { name: "Reverse", text: "Reverses the turn order." },
  BURNER: { name: "Burner", text: "Clears the pile, and the same player plays again." }
};

const RANK_NAMES: Record<number, string> = { 11: "Jack", 12: "Queen", 13: "King", 14: "Ace" };

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function rankName(value: number) {
  return RANK_NAMES[value] ?? String(value);
}

export default function RulesModal({ specialRules, faceUpFailurePickup, onClose }: Props) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    dialogRef.current?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        closeRef.current();
        return;
      }
      const dialog = dialogRef.current;
      if (event.key !== "Tab" || !dialog) return;
      // Keep keyboard focus cycling inside the dialog while it is open.
      const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE));
      const active = document.activeElement;
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const outside = !active || !dialog.contains(active);
      if (event.shiftKey && (active === first || outside)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || outside)) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = previousOverflow;
      opener?.focus();
    };
  }, []);

  return (
    <div
      className="howto-backdrop"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={dialogRef}
        className="howto-dialog glass"
        role="dialog"
        aria-modal="true"
        aria-labelledby="howto-title"
        tabIndex={-1}
      >
        <header className="howto-header">
          <div>
            <div className="badge">Rules</div>
            <h2 className="title" id="howto-title">How to play Shithead</h2>
          </div>
          <button className="howto-close" type="button" onClick={onClose} aria-label="Close rules">
            <Icon name="close" size={18} />
          </button>
        </header>

        <div className="howto-body">
          <h3>Goal</h3>
          <p>Get rid of all your cards first. The last player still holding cards is the Shithead.</p>

          <h3>Your cards</h3>
          <ul>
            <li><strong>Face-down</strong> cards are hidden. You play them blind, last.</li>
            <li><strong>Face-up</strong> cards are visible to everyone.</li>
            <li><strong>Hand</strong> cards are only visible to you.</li>
          </ul>

          <h3>Start of the game</h3>
          <p>
            Once dealt, you may swap one hand card with one face-up card. When you are happy, mark yourself
            ready. Cards are locked after that, and play begins once every player is ready.
          </p>

          <h3>Playing cards</h3>
          <ul>
            <li>On your turn, play one card or several cards of the same rank.</li>
            <li>The card(s) must be equal to or higher than the top card of the pile.</li>
            <li>After playing from your hand, draw from the draw pile until your hand is full again.</li>
            <li>If you cannot or do not want to play, pick up the whole pile into your hand.</li>
          </ul>

          <h3>Order of play</h3>
          <ul>
            <li>Play starts with your hand. Face-up cards can only be played once your hand is empty.</li>
            <li>Face-down cards come last. Choose one to flip blind. If it cannot be played, you pick up the pile together with the flipped card.</li>
            {faceUpFailurePickup && (
              <li>
                Face-up option is on: if the face-up card you choose cannot be played, you pick up the whole pile
                together with that card.
              </li>
            )}
          </ul>

          <h3>Burning the pile</h3>
          <p>
            Four cards of the same rank on the pile burn it (six with two decks). The pile is removed from play and
            the same player goes again.
          </p>

          <h3>Turn order</h3>
          <p>
            Turns go around the table. Players who have no cards left are out and skipped. A Reverse card changes
            the direction of play.
          </p>

          <h3>Special cards in this configuration</h3>
          {specialRules.length === 0 ? (
            <p className="howto-special-empty">No special cards are selected. Every rank follows the standard rule.</p>
          ) : (
            <ul className="howto-special-list">
              {specialRules.map((item) => (
                <li key={item.value}>
                  <span className="howto-special-name">
                    {RULE_TEXT[item.rule].name} · {rankName(item.value)}
                  </span>
                  <span>{RULE_TEXT[item.rule].text}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
