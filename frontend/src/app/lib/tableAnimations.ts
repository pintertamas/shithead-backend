import type { Card, GameStateView, PlayerState } from "../api/game";
import { cardRank, isRedSuit, suitSymbol } from "../components/CardFace";

/*
 * Derives table animations by diffing two consecutive game states on the client. The server only
 * pushes full snapshots, so every effect here is a short Web Animations API flight or pulse that is
 * appended to the overlay layer (`.table-fx`) and removed when it finishes. Nothing here blocks input,
 * and everything is skipped when the user prefers reduced motion.
 */

const PLAY_MS = 420;
const DRAW_MS = 360;
const SWEEP_MS = 480;
const BURN_MS = 520;
const PULSE_MS = 650;
const PLAY_TO_DRAW_MS = 260;
const MAX_FLIGHTS = 6;
const MAX_DRAWS = 4;
const DEFAULT_CARD_SIZE = { width: 52, height: 73 };
const EASING = "cubic-bezier(0.22, 0.8, 0.3, 1)";

type Point = { x: number; y: number };
type Size = { width: number; height: number };

export function prefersReducedMotion() {
  return typeof window !== "undefined"
    && typeof window.matchMedia === "function"
    && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Cards a player currently has on the table or in hand (used to tell pickups and plays apart). */
function tableTotal(player: PlayerState | undefined) {
  return player ? player.handCount + player.faceUp.length + player.faceDownCount : 0;
}

function seatElement(board: HTMLElement, playerId: string): HTMLElement | null {
  const seats = board.querySelectorAll<HTMLElement>("[data-seat-id]");
  for (const seat of Array.from(seats)) {
    if (seat.dataset.seatId === playerId) return seat;
  }
  return null;
}

function centerOf(element: Element | null | undefined, origin: DOMRect): Point | null {
  if (!element) return null;
  const rect = element.getBoundingClientRect();
  if (rect.width === 0 && rect.height === 0) return null;
  return { x: rect.left - origin.left + rect.width / 2, y: rect.top - origin.top + rect.height / 2 };
}

function cornerNode(card: Card) {
  const corner = document.createElement("span");
  corner.append(cardRank(card.value), document.createElement("br"), suitSymbol(card.suit));
  return corner;
}

function createCardElement(card: Card | null, size: Size): HTMLElement {
  const el = document.createElement("div");
  el.setAttribute("aria-hidden", "true");
  if (card) {
    el.className = `playing-card face-up-card table-fx-card${isRedSuit(card.suit) ? " red-card" : ""}`;
    const corner = cornerNode(card);
    corner.className = "playing-card-corner";
    const opposite = cornerNode(card);
    opposite.className = "playing-card-corner-opposite";
    const center = document.createElement("span");
    center.className = "playing-card-center";
    center.textContent = suitSymbol(card.suit);
    el.append(corner, opposite, center);
  } else {
    el.className = "playing-card face-down-card table-fx-card";
  }
  el.style.width = `${size.width}px`;
  el.style.height = `${size.height}px`;
  return el;
}

function runOnLayer(layer: HTMLElement, el: HTMLElement, keyframes: Keyframe[], options: KeyframeAnimationOptions) {
  layer.appendChild(el);
  if (typeof el.animate !== "function") {
    el.remove();
    return;
  }
  const animation = el.animate(keyframes, { fill: "both", ...options });
  animation.onfinish = () => el.remove();
  animation.oncancel = () => el.remove();
}

/** A card (or card back) that travels from one table point to another. */
function flyCard(layer: HTMLElement, card: Card | null, size: Size, from: Point, to: Point, delay: number, duration: number) {
  const startX = from.x - size.width / 2;
  const startY = from.y - size.height / 2;
  const endX = to.x - size.width / 2;
  const endY = to.y - size.height / 2;
  runOnLayer(layer, createCardElement(card, size), [
    { transform: `translate(${startX}px, ${startY}px) scale(0.8)`, opacity: 0 },
    { transform: `translate(${startX}px, ${startY}px) scale(1)`, opacity: 1, offset: 0.14 },
    { transform: `translate(${endX}px, ${endY}px) scale(1)`, opacity: 1, offset: 0.82 },
    { transform: `translate(${endX}px, ${endY}px) scale(0.85)`, opacity: 0 }
  ], { duration, delay, easing: EASING });
}

/** A card that flashes and fades out where the discard pile was (burned pile). */
function burnCard(layer: HTMLElement, card: Card, size: Size, at: Point, index: number, count: number, delay: number) {
  const spread = index - (count - 1) / 2;
  const x = at.x - size.width / 2 + spread * 3;
  const y = at.y - size.height / 2 - Math.abs(spread);
  const tilt = spread * 3;
  runOnLayer(layer, createCardElement(card, size), [
    { transform: `translate(${x}px, ${y}px) rotate(${tilt}deg) scale(1)`, opacity: 1, filter: "brightness(1)" },
    { transform: `translate(${x}px, ${y}px) rotate(${tilt}deg) scale(1.06)`, opacity: 1, filter: "brightness(2.2) sepia(1) saturate(4) hue-rotate(-25deg)", offset: 0.35 },
    { transform: `translate(${x}px, ${y - 8}px) rotate(${tilt}deg) scale(0.92)`, opacity: 0, filter: "brightness(1.2)" }
  ], { duration: BURN_MS, delay, easing: "ease-out" });
}

/**
 * Plays the transitions implied by moving from `prev` to `next`:
 * - cards played onto the discard pile fly from the acting player's seat,
 * - a pickup sweeps the discard pile to the player who took it,
 * - a burn flashes the discard pile away (after the played card lands),
 * - cards drawn from the draw pile fly to the acting player,
 * - the newly active seat gets a one-off pulse.
 */
export function playTableTransitions(prev: GameStateView, next: GameStateView, layer: HTMLElement) {
  if (prefersReducedMotion() || !prev.started || !next.started) return;
  const board = layer.parentElement;
  if (!board) return;
  const origin = layer.getBoundingClientRect();
  const seatPoint = (playerId: string | null) => (playerId ? centerOf(seatElement(board, playerId), origin) : null);
  const discardPoint = centerOf(board.querySelector('[data-fx="discard"]'), origin);
  const drawPoint = centerOf(board.querySelector('[data-fx="draw"]'), origin);
  const cardRect = board.querySelector(".game-piles .playing-card, .game-piles .pile-slot")?.getBoundingClientRect();
  const size: Size = cardRect && cardRect.width > 0 ? { width: cardRect.width, height: cardRect.height } : DEFAULT_CARD_SIZE;

  const actorId = prev.currentPlayerId;
  const actorBefore = prev.players.find((player) => player.playerId === actorId);
  const actorAfter = next.players.find((player) => player.playerId === actorId);
  const actorPoint = seatPoint(actorId);
  let playStarted = false;

  if (prev.discardCount > 0 && next.discardCount === 0) {
    const pickedUp = Boolean(actorBefore && actorAfter && prev.deckCount === next.deckCount
      && tableTotal(actorAfter) > tableTotal(actorBefore));
    const pickerPoint = pickedUp ? actorPoint : null;
    if (pickedUp && pickerPoint && discardPoint) {
      const count = Math.min(prev.discardCount, MAX_FLIGHTS);
      const cards = prev.discardPile.slice(-count);
      for (let index = 0; index < count; index++) {
        // Only the picker sees the cards they take; everyone else sees card backs.
        const card = actorAfter?.isYou ? cards[index] ?? null : null;
        flyCard(layer, card, size, discardPoint, pickerPoint, index * 45, SWEEP_MS);
      }
    } else if (discardPoint) {
      let burnDelay = 0;
      if (actorPoint && actorBefore && actorAfter && tableTotal(actorAfter) < tableTotal(actorBefore)) {
        flyCard(layer, null, size, actorPoint, discardPoint, 0, PLAY_MS);
        burnDelay = PLAY_MS * 0.7;
        playStarted = true;
      }
      const count = Math.min(prev.discardCount, MAX_FLIGHTS);
      const cards = prev.discardPile.slice(-count);
      for (let index = 0; index < cards.length; index++) {
        burnCard(layer, cards[index], size, discardPoint, index, cards.length, burnDelay);
      }
    }
  } else if (next.discardCount > prev.discardCount && actorPoint && discardPoint) {
    const added = next.discardCount - prev.discardCount;
    const count = Math.min(added, MAX_FLIGHTS);
    const newCards = next.discardPile.length === next.discardCount ? next.discardPile.slice(prev.discardCount) : [];
    for (let index = 0; index < count; index++) {
      flyCard(layer, newCards[index] ?? null, size, actorPoint, discardPoint, index * 70, PLAY_MS);
    }
    playStarted = true;
  }

  const drawn = prev.deckCount - next.deckCount;
  if (drawn > 0 && drawPoint && actorPoint) {
    const count = Math.min(drawn, MAX_DRAWS);
    const drawStart = playStarted ? PLAY_TO_DRAW_MS : 0;
    for (let index = 0; index < count; index++) {
      flyCard(layer, null, size, drawPoint, actorPoint, drawStart + index * 90, DRAW_MS);
    }
  }

  if (next.currentPlayerId && next.currentPlayerId !== prev.currentPlayerId) {
    const seat = seatElement(board, next.currentPlayerId);
    seat?.animate?.(
      [
        { boxShadow: "0 0 0 0 rgba(242, 196, 111, 0.8)" },
        { boxShadow: "0 0 0 16px rgba(242, 196, 111, 0)" }
      ],
      { duration: PULSE_MS, easing: "ease-out" }
    );
  }
}
