import { FocusEvent as ReactFocusEvent, MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent, ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import "../styles/overlays.css";

type Props = {
  className?: string;
  /** Accessible name of the toggle button and of the overlay. */
  label: string;
  /** Visible text of the toggle button. */
  toggleText: string;
  /** Kept for compatibility; desktop overlays are centred on the element, mobile ones are centred in the viewport. */
  placement?: "above" | "below";
  popover: ReactNode;
  children: ReactNode;
  /** On phones, tapping the wrapped element itself also opens the overlay. */
  pressToOpen?: boolean;
};

const MOBILE_QUERY = "(max-width: 700px), (pointer: coarse)";
const FINE_HOVER_QUERY = "(hover: hover) and (pointer: fine)";
const EDGE = 8;
const HIDE_DELAY_MS = 150;

function matches(query: string) {
  return typeof window !== "undefined" && typeof window.matchMedia === "function" && window.matchMedia(query).matches;
}

function clamp(value: number, low: number, high: number) {
  return Math.max(low, Math.min(high, value));
}

function useIsMobile() {
  const [mobile, setMobile] = useState(() => matches(MOBILE_QUERY));
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const query = window.matchMedia(MOBILE_QUERY);
    const update = () => setMobile(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return mobile;
}

/**
 * Wraps a table element with a peek overlay. The overlay is portalled to document.body and is
 * position: fixed, so it never affects layout. Mouse and keyboard users get it on hover or focus
 * (anchored over the element); touch users tap the toggle (or the element when pressToOpen) to get
 * a centred dialog with a backdrop that closes on tap outside, Escape or the close button.
 */
export default function PeekWrap({ className = "", label, toggleText, placement = "above", popover, children, pressToOpen = false }: Props) {
  const id = useId();
  const mobile = useIsMobile();
  // pinned: opened by tap, click or keyboard on the toggle. hovered / focused: desktop pointer or focus.
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

  // Tapping or clicking outside a pinned overlay closes it (the mobile backdrop is also outside).
  useEffect(() => {
    if (!pinned) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!wrapperRef.current?.contains(target) && !popoverRef.current?.contains(target)) setPinned(false);
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [pinned]);

  // Mobile dialog takes focus when it opens.
  useEffect(() => {
    if (pinned && mobile) popoverRef.current?.focus({ preventScroll: true });
  }, [pinned, mobile]);

  // Desktop: centre the overlay on the wrapped element, clamped inside the viewport. Positioned with
  // fixed coordinates only, so the document never changes size.
  useLayoutEffect(() => {
    if (!open || mobile) return;
    const place = () => {
      const overlay = popoverRef.current;
      const anchor = wrapperRef.current?.firstElementChild;
      if (!overlay || !anchor) return;
      const a = anchor.getBoundingClientRect();
      const p = overlay.getBoundingClientRect();
      const viewportWidth = document.documentElement.clientWidth;
      const viewportHeight = window.innerHeight;
      const left = clamp(a.left + a.width / 2 - p.width / 2, EDGE, viewportWidth - p.width - EDGE);
      const top = clamp(a.top + a.height / 2 - p.height / 2, EDGE, viewportHeight - p.height - EDGE);
      overlay.style.left = `${left}px`;
      overlay.style.top = `${top}px`;
    };
    place();
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, true);
    return () => {
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, true);
    };
  }, [open, mobile]);

  // Desktop hover: a short delay lets the pointer move from the element onto the overlay.
  const onPointerEnter = (event: ReactPointerEvent) => {
    if (event.pointerType === "touch" || matches(MOBILE_QUERY)) return;
    cancelHide();
    setHovered(true);
  };
  const onPointerLeave = (event: ReactPointerEvent) => {
    if (event.pointerType === "touch") return;
    cancelHide();
    hideTimer.current = window.setTimeout(() => setHovered(false), HIDE_DELAY_MS);
  };

  const onFocusIn = () => {
    if (!matches(MOBILE_QUERY) && matches(FINE_HOVER_QUERY)) setFocused(true);
  };
  const onFocusOut = (event: ReactFocusEvent) => {
    const next = event.relatedTarget as Node | null;
    if (next && (wrapperRef.current?.contains(next) || popoverRef.current?.contains(next))) return;
    setFocused(false);
  };

  const onWrapperClick = (event: ReactMouseEvent<HTMLDivElement>) => {
    if (!pressToOpen || !matches(MOBILE_QUERY)) return;
    if (toggleRef.current?.contains(event.target as Node)) return;
    setPinned((value) => !value);
  };

  const closeFromButton = () => {
    setPinned(false);
    toggleRef.current?.focus();
  };

  const overlay = open
    ? createPortal(
        <>
          {mobile && pinned && <div className="peek-overlay-backdrop" aria-hidden="true" />}
          <div
            ref={popoverRef}
            id={id}
            className={`peek-overlay ${mobile ? "peek-overlay-mobile" : "peek-overlay-desktop"}`}
            role={mobile ? "dialog" : "region"}
            aria-modal={mobile ? true : undefined}
            aria-label={label}
            tabIndex={mobile ? -1 : undefined}
            onPointerEnter={onPointerEnter}
            onPointerLeave={onPointerLeave}
            onBlur={onFocusOut}
          >
            {mobile && pinned && (
              <button type="button" className="peek-close" aria-label="Close" onClick={closeFromButton}>×</button>
            )}
            {popover}
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
