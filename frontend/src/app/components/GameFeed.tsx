import { useEffect, useRef, useState } from "react";
import type { GameEvent } from "../api/game";
import { describeEvent } from "../lib/gameFeed";
import Icon from "./Icon";
import "../styles/feed.css";

const BANNER_MS = 4000;
const WIDE_SCREEN_QUERY = "(min-width: 900px)";

function isWideScreen(): boolean {
  try {
    return window.matchMedia(WIDE_SCREEN_QUERY).matches;
  } catch {
    return true;
  }
}

type Props = {
  events?: GameEvent[] | null;
};

/** Newest-first activity log. Collapsed by default on narrow screens; the newest event also shows as a short banner. */
export default function GameFeed({ events }: Props) {
  const list = events ?? [];
  const newest = list.length > 0 ? list[list.length - 1] : null;
  const newestSeq = newest?.seq ?? 0;
  const [open, setOpen] = useState(isWideScreen);
  const [banner, setBanner] = useState<GameEvent | null>(null);
  // Start from the newest event already loaded so existing history does not replay as a banner.
  const announcedSeq = useRef(newestSeq);

  // Keyed on the sequence number, not the object: the table re-renders with fresh objects on every poll.
  useEffect(() => {
    if (!newest || newest.seq <= announcedSeq.current) return undefined;
    announcedSeq.current = newest.seq;
    setBanner(newest);
    const timeout = window.setTimeout(() => setBanner(null), BANNER_MS);
    return () => window.clearTimeout(timeout);
  }, [newestSeq]);

  return (
    <section className="game-feed glass" aria-label="Game log">
      <button
        type="button"
        className="game-feed-toggle"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span>Game log</span>
        <span className="game-feed-count">{list.length}</span>
        <Icon name={open ? "chevron-up" : "chevron-down"} className="game-feed-chevron" />
      </button>
      {banner && (
        <div key={banner.seq} className="game-feed-banner" role="status" data-type={banner.type}>
          {describeEvent(banner)}
        </div>
      )}
      {open && (list.length === 0
        ? <p className="game-feed-empty">Moves and events will show up here.</p>
        : (
          <ol className="game-feed-list">
            {[...list].reverse().map((event) => (
              <li key={event.seq} className="game-feed-item" data-type={event.type}>
                {describeEvent(event)}
              </li>
            ))}
          </ol>
        ))}
    </section>
  );
}
