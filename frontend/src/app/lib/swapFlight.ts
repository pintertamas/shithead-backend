import { useCallback, useEffect, useLayoutEffect, useRef, type RefObject } from "react";
import type { Card, GameStateView } from "../api/game";
import { prefersReducedMotion } from "./tableAnimations";

/*
 * Swap flights. In the setup phase the viewer swaps hand cards with face-up cards. The table is then painted in its
 * new layout, and each card that changed zone glides from its old box to its new one (FLIP): the old boxes are read
 * before the update renders, the new ones after the commit, and a copy of the card animates between them.
 *
 * The copy lives in the table's fx overlay (.table-fx, pointer-events: none) because .playing-hand and the face-up
 * stacks clip their overflow, so a card animated in place would be cut off at their edges. The real card stays in its
 * final slot; it is hidden (opacity 0) only while its copy flies, and it keeps receiving clicks. Opponents' cards
 * are never animated, and nothing runs outside the setup phase or under prefers-reduced-motion.
 *
 * The own cards are found by data-swap-card (suit|value|rule, set in PlayerPanel). Their zone is the DOM ancestry:
 * .playing-hand for hand cards, anything else for face-up cards.
 */

export const SWAP_FLIGHT_MS = 420;
/** Same curve as the play animations in lib/tableAnimations.ts. */
const EASING = "cubic-bezier(0.22, 0.8, 0.3, 1)";
const OWN_CARD_SELECTOR = ".game-seat-own [data-swap-card]";

export type SwapZone = "hand" | "faceUp";
/** A viewer card: its zone, its index inside that zone (DOM order) and its identity (suit|value|rule). */
export type SwapCardRef = { zone: SwapZone; index: number; id: string };
export type SwapBox = { left: number; top: number; width: number; height: number };

type Flight = { animation: Animation; real: HTMLElement; copy: HTMLElement };
type OwnCardSnapshot = { ref: SwapCardRef; box: SwapBox }[];

/** Setup (swap) phase: the game has started and not every player is ready yet. */
export function isSetupPhase(state: GameStateView | null | undefined): boolean {
  return Boolean(state && state.started && !state.setupComplete);
}

/**
 * Pairs every card that changed zone with the same card in its new zone (hand to face-up, or back). Cards that
 * stayed in their zone are matched first, same zone and index before same zone, so an identical card that did not
 * move is never taken for a moved one. Returns [indexInBefore, indexInAfter] for the moved cards only, sorted by
 * the first index.
 */
export function pairSwappedCards(before: SwapCardRef[], after: SwapCardRef[]): Array<[number, number]> {
  const unmatchedBefore = before.map((_, index) => index);
  const unmatchedAfter = after.map((_, index) => index);
  const moved: Array<[number, number]> = [];
  const match = (sameCard: (b: SwapCardRef, a: SwapCardRef) => boolean, keep: boolean) => {
    for (const b of [...unmatchedBefore]) {
      const a = unmatchedAfter.find((candidate) => sameCard(before[b], after[candidate]));
      if (a === undefined) continue;
      unmatchedBefore.splice(unmatchedBefore.indexOf(b), 1);
      unmatchedAfter.splice(unmatchedAfter.indexOf(a), 1);
      if (keep) moved.push([b, a]);
    }
  };
  match((b, a) => a.zone === b.zone && a.id === b.id && a.index === b.index, false);
  match((b, a) => a.zone === b.zone && a.id === b.id, false);
  match((b, a) => a.zone !== b.zone && a.id === b.id, true);
  return moved.sort((x, y) => x[0] - y[0]);
}

/**
 * The flights to play for one state change: the moved pairs in a setup-phase update, none otherwise (plain refreshes,
 * play and pickup phases, and reduced motion).
 */
export function planSwapFlights(
  before: SwapCardRef[],
  after: SwapCardRef[],
  options: { setup: boolean; reducedMotion: boolean }
): Array<[number, number]> {
  if (!options.setup || options.reducedMotion) return [];
  return pairSwappedCards(before, after);
}

function cardRefs(elements: HTMLElement[]): SwapCardRef[] {
  const counts: Record<SwapZone, number> = { hand: 0, faceUp: 0 };
  return elements.map((element) => {
    const zone: SwapZone = element.closest(".playing-hand") ? "hand" : "faceUp";
    return { zone, index: counts[zone]++, id: element.dataset.swapCard ?? "" };
  });
}

function ownCardElements(board: HTMLElement): HTMLElement[] {
  return Array.from(board.querySelectorAll<HTMLElement>(OWN_CARD_SELECTOR));
}

function boxOf(element: Element): SwapBox {
  const rect = element.getBoundingClientRect();
  return { left: rect.left, top: rect.top, width: rect.width, height: rect.height };
}

function sameIds(a: Card[] | undefined, b: Card[] | undefined) {
  const left = a ?? [];
  const right = b ?? [];
  return left.length === right.length
    && left.every((card, index) => card.suit === right[index].suit && card.value === right[index].value && card.rule === right[index].rule);
}

/** True when the viewer's hand or face-up cards differ between the two states (the only updates that can be a swap). */
function ownCardsChanged(previous: GameStateView, next: GameStateView): boolean {
  const before = previous.players.find((player) => player.isYou);
  const now = next.players.find((player) => player.isYou);
  if (!before || !now) return false;
  return !sameIds(before.hand, now.hand) || !sameIds(before.faceUp, now.faceUp);
}

/** The viewer's cards as they are on screen now. A card still flying is measured where its copy is. */
function snapshotOwnCards(board: HTMLElement, flights: Set<Flight>): OwnCardSnapshot {
  const elements = ownCardElements(board);
  return cardRefs(elements).map((ref, index) => {
    const element = elements[index];
    const flight = Array.from(flights).find((candidate) => candidate.real === element);
    return { ref, box: boxOf(flight ? flight.copy : element) };
  });
}

/** Ends a flight once: removes its copy and shows the real card again. Calling it again does nothing. */
function endFlight(flights: Set<Flight>, flight: Flight) {
  if (!flights.delete(flight)) return;
  flight.copy.remove();
  flight.real.style.opacity = "";
}

/** A new flight for a card replaces any flight still running for the same real card. */
function cancelFlightsOn(flights: Set<Flight>, real: HTMLElement) {
  for (const flight of Array.from(flights)) {
    if (flight.real !== real) continue;
    flight.animation.cancel();
    endFlight(flights, flight);
  }
}

/** A plain copy of a face-up card: same markup and colour hook, no selection state, and not focusable or read out. */
function createCopy(real: HTMLElement): HTMLElement {
  const copy = document.createElement("div");
  copy.className = "playing-card face-up-card";
  copy.setAttribute("aria-hidden", "true");
  const label = real.getAttribute("aria-label");
  if (label) copy.setAttribute("aria-label", label.replace(/, selected$/, ""));
  for (const child of Array.from(real.childNodes)) copy.appendChild(child.cloneNode(true));
  return copy;
}

/** Flies a copy of `real` from `from` (the old box) to where `real` now sits. */
function playFlight(flights: Set<Flight>, layer: HTMLElement, real: HTMLElement, from: SwapBox, origin: DOMRect) {
  cancelFlightsOn(flights, real);
  const to = boxOf(real);
  if (to.width <= 0 || to.height <= 0 || from.width <= 0 || from.height <= 0) return;

  const copy = createCopy(real);
  Object.assign(copy.style, {
    position: "absolute",
    left: `${to.left - origin.left}px`,
    top: `${to.top - origin.top}px`,
    width: `${to.width}px`,
    height: `${to.height}px`,
    margin: "0",
    boxSizing: "border-box",
    pointerEvents: "none",
    transformOrigin: "50% 50%",
    willChange: "transform"
  });
  layer.appendChild(copy);

  // Inverted FLIP: the copy starts offset (and scaled) from its new box by the old box's centre and size, then runs to the new box.
  const dx = (from.left + from.width / 2) - (to.left + to.width / 2);
  const dy = (from.top + from.height / 2) - (to.top + to.height / 2);
  const animation = copy.animate([
    { transform: `translate(${dx}px, ${dy}px) scale(${from.width / to.width}, ${from.height / to.height})` },
    { transform: "translate(0px, 0px) scale(1, 1)" }
  ], { duration: SWAP_FLIGHT_MS, easing: EASING });
  const flight: Flight = { animation, real, copy };
  flights.add(flight);
  real.style.opacity = "0";
  animation.onfinish = () => endFlight(flights, flight);
  animation.oncancel = () => endFlight(flights, flight);
}

/**
 * Wires the swap flights into the table. Call `captureBeforeUpdate(previous, next)` in the state update path before
 * the new state is stored, while the table still shows `previous`. The flights are played in a layout effect after
 * the new state has been committed, so the first painted frame already shows each card at its old box.
 */
export function useSwapFlights(
  boardRef: RefObject<HTMLElement>,
  layerRef: RefObject<HTMLElement>,
  state: GameStateView | null
): (previous: GameStateView | null, next: GameStateView) => void {
  const flightsRef = useRef<Set<Flight>>(new Set());
  const snapshotRef = useRef<OwnCardSnapshot | null>(null);

  const captureBeforeUpdate = useCallback((previous: GameStateView | null, next: GameStateView) => {
    const board = boardRef.current;
    if (!board || !previous || !isSetupPhase(previous) || !isSetupPhase(next)) return;
    if (!ownCardsChanged(previous, next) || prefersReducedMotion()) return;
    snapshotRef.current = snapshotOwnCards(board, flightsRef.current);
  }, [boardRef]);

  useLayoutEffect(() => {
    const snapshot = snapshotRef.current;
    // Consumed by the next commit. A commit without a snapshot (every poll, every other update) plays nothing.
    snapshotRef.current = null;
    const board = boardRef.current;
    const layer = layerRef.current;
    if (!snapshot || !board || !layer || !isSetupPhase(state)) return;
    if (typeof layer.animate !== "function") return;
    const elements = ownCardElements(board);
    const pairs = planSwapFlights(snapshot.map((card) => card.ref), cardRefs(elements), {
      setup: true,
      reducedMotion: prefersReducedMotion()
    });
    if (pairs.length === 0) return;
    const origin = layer.getBoundingClientRect();
    for (const [beforeIndex, afterIndex] of pairs) {
      playFlight(flightsRef.current, layer, elements[afterIndex], snapshot[beforeIndex].box, origin);
    }
  }, [state, boardRef, layerRef]);

  // Unmount: stop every flight and drop its copy, so no animation outlives the table.
  useEffect(() => {
    const flights = flightsRef.current;
    return () => {
      for (const flight of Array.from(flights)) {
        flight.animation.cancel();
        endFlight(flights, flight);
      }
    };
  }, []);

  return captureBeforeUpdate;
}
