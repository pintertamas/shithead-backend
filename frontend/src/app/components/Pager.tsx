import { useEffect, useRef } from "react";
import "../styles/pagination.css";

type PagerProps = {
  /** Accessible name for the navigation landmark, e.g. "Leaderboard pages". */
  label: string;
  /** Current 1-based page, already clamped to the valid range by the caller. */
  page: number;
  totalPages: number;
  /** 1-based inclusive range of the items on the current page. */
  rangeStart: number;
  rangeEnd: number;
  total: number;
  /** Plural noun for the items, e.g. "players". */
  noun: string;
  onPageChange: (page: number) => void;
};

/**
 * Previous / Next pager with a "Page X of Y" status and an "a–b of N" range. Buttons are disabled at the ends.
 * When the button that was pressed becomes disabled, focus moves to the other button so keyboard users keep their place.
 */
export default function Pager({ label, page, totalPages, rangeStart, rangeEnd, total, noun, onPageChange }: PagerProps) {
  const previousRef = useRef<HTMLButtonElement>(null);
  const nextRef = useRef<HTMLButtonElement>(null);
  const focusAfterRender = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    const target = focusAfterRender.current;
    if (!target) return;
    focusAfterRender.current = null;
    target.focus({ preventScroll: true });
  }, [page]);

  const atStart = page <= 1;
  const atEnd = page >= totalPages;

  return (
    <nav className="pager" aria-label={label}>
      <button
        ref={previousRef}
        type="button"
        className="button secondary pager-button"
        aria-label="Previous page"
        disabled={atStart}
        onClick={() => {
          if (page - 1 <= 1) focusAfterRender.current = nextRef.current;
          onPageChange(page - 1);
        }}
      >
        Previous
      </button>
      <p className="pager-status" aria-live="polite">
        <span>Page {page} of {totalPages}</span>
        <span className="pager-range">{rangeStart}–{rangeEnd} of {total} {noun}</span>
      </p>
      <button
        ref={nextRef}
        type="button"
        className="button secondary pager-button"
        aria-label="Next page"
        disabled={atEnd}
        onClick={() => {
          if (page + 1 >= totalPages) focusAfterRender.current = previousRef.current;
          onPageChange(page + 1);
        }}
      >
        Next
      </button>
    </nav>
  );
}
