import { FocusEvent as ReactFocusEvent, MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent, ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import "../styles/overlays.css";

type Props = {
  className?: string;
  /** Extra class on the overlay element (used by dialog-style content such as the discard pile). */
  dialogClassName?: string;
  /** Accessible name of the toggle button and of the overlay. */
  label: string;
  /** Visible text of the toggle button. */
  toggleText: string;
  /** Kept for compatibility. Desktop overlays are placed above the element (else below or beside), dialogs are centred in the viewport. */
  placement?: "above" | "below";
  /** Content of the overlay. A function receives whether the overlay is shown as a dialog (touch devices). */
  popover: ReactNode | ((dialog: boolean) => ReactNode);
  children: ReactNode;
  /** On touch devices, tapping the wrapped element itself also opens the overlay. */
  pressToOpen?: boolean;
};

/**
 * Hover and focus behaviour follow the input capability, not the width: a device with a fine pointer that
 * can hover gets the anchored overlay at any window width. Only devices without hover (phones, tablets)
 * get the tap-to-open dialog. The phone layout of the table is a separate, width-based concern.
 */
const HOVER_QUERY = "(hover: hover) and (pointer: fine)";
const EDGE = 8;
/** Space between the wrapped element and an anchored overlay. */
const GAP = 10;
const MIN_OVERLAY_HEIGHT = 80;
const HIDE_DELAY_MS = 150;

function matches(query: string) {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia(query).matches;
}

function clamp(value: number, low: number, high: number) {
  return Math.max(low, Math.min(high, value));
}

function useCanHover() {
  const [canHover, setCanHover] = useState(() => matches(HOVER_QUERY));
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const query = window.matchMedia(HOVER_QUERY);
    const update = () => setCanHover(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return canHover;
}

/**
 * Wraps a table element with a peek overlay. The overlay is portalled to document.body and is
 * position: fixed, so it never affects layout. Devices with hover get it on pointer hover or keyboard
 * focus, placed above the element (else below or beside) and kept inside the viewport. A hover
 * preview is click-through (pointer-events: none) so it never blocks the element it belongs to; it only
 * takes pointer events once pinned. Touch devices tap the toggle (or the
 * element when pressToOpen) to get a centred dialog with a backdrop that closes on tap outside, Escape or
 * the close button.
 */
export default function PeekWrap({ className = "", dialogClassName = "", label, toggleText, placement = "above", popover, children, pressToOpen = false }: Props) {
  const id = useId();
  const canHover = useCanHover();
  const dialog = !canHover;
  // pinned: opened by tap, click or keyboard on the toggle. hovered / focused: pointer or focus on a hover device.
  const [pinned, setPinned] = useState(false);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const hideTimer = useRef<number | undefined>(undefined);
  const open = pinned || hovered || focused;

  const cancelHide = () => {
    window.clearTimeout(hideTimer.current);
    hideTimer.current = undefined;
  };

  useEffect(() => cancelHide, []);

  // Leaving hover mode (or the pointer changing) drops any hover state.
  useEffect(() => {
    if (dialog) {
      cancelHide();
      setHovered(false);
      setFocused(false);
    } else {
      setPinned(false);
    }
  }, [dialog]);

  // Escape closes every state; focus returns to the toggle if it was inside the overlay.
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      const focusInside = popoverRef.current?.contains(document.activeElement) ?? false;
      cancelHide();
      setPinned(false);
      setHovered(false);
      setFocused(false);
      if (focusInside) toggleRef.current?.focus();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open]);

  // Tapping or clicking outside a pinned overlay closes it (the dialog backdrop is also outside).
  useEffect(() => {
    if (!pinned) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!wrapperRef.current?.contains(target) && !popoverRef.current?.contains(target)) setPinned(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [pinned]);

  // The dialog takes focus when it opens.
  useEffect(() => {
    if (pinned && dialog) popoverRef.current?.focus({ preventScroll: true });
  }, [pinned, dialog]);

  // Anchored overlay: never over the element it belongs to. Preference: above it, then below, then beside it;
  // when no side fits the whole overlay it takes the roomier vertical side with a max height and scrolls inside.
  // Fixed coordinates only, so the document never changes size. Runs after each render so content changes keep it placed.
  useLayoutEffect(() => {
    if (!open || dialog) return;
    const place = () => {
      const overlay = popoverRef.current;
      const anchor = wrapperRef.current?.firstElementChild;
      if (!overlay || !anchor) return;
      // Measure the natural size first; a max height set by an earlier placement would hide it.
      overlay.style.maxHeight = "";
      const a = anchor.getBoundingClientRect();
      const p = overlay.getBoundingClientRect();
      const viewportWidth = document.documentElement.clientWidth;
      const viewportHeight = window.innerHeight;
      const roomAbove = a.top - GAP - EDGE;
      const roomBelow = viewportHeight - a.bottom - GAP - EDGE;
      const roomLeft = a.left - GAP - EDGE;
      const roomRight = viewportWidth - a.right - GAP - EDGE;
      const centredLeft = clamp(a.left + a.width / 2 - p.width / 2, EDGE, Math.max(EDGE, viewportWidth - p.width - EDGE));
      const centredTop = clamp(a.top + a.height / 2 - p.height / 2, EDGE, Math.max(EDGE, viewportHeight - p.height - EDGE));
      let left = centredLeft;
      let top = 0;
      let maxHeight: number | null = null;
      if (p.height <= roomAbove) {
        top = a.top - GAP - p.height;
      } else if (p.height <= roomBelow) {
        top = a.bottom + GAP;
      } else if (p.height <= viewportHeight - EDGE * 2 && (p.width <= roomRight || p.width <= roomLeft)) {
        top = centredTop;
        left = p.width <= roomRight ? a.right + GAP : a.left - GAP - p.width;
      } else if (roomAbove >= roomBelow) {
        maxHeight = Math.max(MIN_OVERLAY_HEIGHT, roomAbove);
        top = Math.max(EDGE, a.top - GAP - maxHeight);
      } else {
        maxHeight = Math.max(MIN_OVERLAY_HEIGHT, roomBelow);
        top = a.bottom + GAP;
      }
      overlay.style.maxHeight = maxHeight === null ? "" : `${maxHeight}px`;
      overlay.style.left = `${clamp(left, EDGE, Math.max(EDGE, viewportWidth - p.width - EDGE))}px`;
      overlay.style.top = `${clamp(top, EDGE, Math.max(EDGE, viewportHeight - (maxHeight ?? p.height) - EDGE))}px`;
    };
    place();
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, true);
    return () => {
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, true);
    };
  });

  // Hover: a short delay lets the pointer move from the element onto the overlay.
  const onPointerEnter = (event: ReactPointerEvent) => {
    if (event.pointerType === "touch" || !canHover) return;
    cancelHide();
    setHovered(true);
  };
  const onPointerLeave = (event: ReactPointerEvent) => {
    if (event.pointerType === "touch" || !canHover) return;
    cancelHide();
    hideTimer.current = window.setTimeout(() => setHovered(false), HIDE_DELAY_MS);
  };

  const onFocusIn = () => {
    if (canHover) setFocused(true);
  };
  const onFocusOut = (event: ReactFocusEvent) => {
    const next = event.relatedTarget as Node | null;
    if (next && (wrapperRef.current?.contains(next) || popoverRef.current?.contains(next))) return;
    setFocused(false);
  };

  const onWrapperClick = (event: ReactMouseEvent<HTMLDivElement>) => {
    if (!pressToOpen || !dialog) return;
    if (toggleRef.current?.contains(event.target as Node)) return;
    setPinned((value) => !value);
  };

  const closeFromButton = () => {
    setPinned(false);
    toggleRef.current?.focus();
  };

  const content = typeof popover === "function" ? popover(dialog) : popover;

  const overlay = open
    ? createPortal(
        <>
          {dialog && pinned && <div className="peek-overlay-backdrop" aria-hidden="true" />}
          <div
            ref={popoverRef}
            id={id}
            className={`peek-overlay ${dialog ? `peek-overlay-mobile${dialogClassName ? ` ${dialogClassName}` : ""}` : "peek-overlay-desktop"}${dialog || pinned ? "" : " peek-overlay-passive"}`}
            role={dialog ? "dialog" : "region"}
            aria-modal={dialog ? true : undefined}
            aria-label={label}
            tabIndex={dialog ? -1 : undefined}
            onPointerEnter={onPointerEnter}
            onPointerLeave={onPointerLeave}
            onBlur={onFocusOut}
          >
            {dialog && pinned && (
              <button type="button" className="peek-close" aria-label="Close" onClick={closeFromButton}>×</button>
            )}
            {content}
          </div>
        </>,
        document.body
      )
    : null;

  return (
    <>
      <div
        ref={wrapperRef}
        className={`peek-wrap peek-${placement}${open ? " peek-open" : ""} ${className}`.trim()}
        onPointerEnter={onPointerEnter}
        onPointerLeave={onPointerLeave}
        onFocus={onFocusIn}
        onBlur={onFocusOut}
        onClick={pressToOpen ? onWrapperClick : undefined}
      >
        {children}
        <button
          ref={toggleRef}
          type="button"
          className="peek-toggle"
          aria-label={label}
          aria-expanded={open}
          aria-controls={open ? id : undefined}
          onClick={() => setPinned((value) => !value)}
        >
          {toggleText}
        </button>
      </div>
      {overlay}
    </>
  );
}
