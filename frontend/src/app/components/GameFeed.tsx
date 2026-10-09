import { useEffect, useRef, useState } from "react";
import type { GameEvent } from "../api/game";
import { describeEvent } from "../lib/gameFeed";
import Icon from "./Icon";
import "../styles/feed.css";
import "../styles/scrollbars.css";

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

/**
 * Newest-first activity log. Always open on wide screens. Collapsed by default on narrow screens, where the
 * newest event also shows as a short banner. The list has a fixed height and scrolls inside.
 */
export default function GameFeed({ events }: Props) {
  const list = events ?? [];
  const newest = list.length > 0 ? list[list.length - 1] : null;
  const newestSeq = newest?.seq ?? 0;
  const [wide, setWide] = useState(isWideScreen);
  const [expanded, setExpanded] = useState(false);
  const open = wide || expanded;
  const [banner, setBanner] = useState<GameEvent | null>(null);
  // Start from the newest event already loaded so existing history does not replay as a banner.
  const announcedSeq = useRef(newestSeq);

  useEffect(() => {
    let query: MediaQueryList;
    try {
      query = window.matchMedia(WIDE_SCREEN_QUERY);
    } catch {
      return undefined;
    }
    const onChange = () => setWide(query.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  // Keyed on the sequence number, not the object: the table re-renders with fresh objects on every poll.
  useEffect(() => {
    if (!newest || newest.seq <= announcedSeq.current) return undefined;
    announcedSeq.current = newest.seq;
    setBanner(newest);
    const timeout = window.setTimeout(() => setBanner(null), BANNER_MS);
    return () => window.clearTimeout(timeout);
  }, [newestSeq]);

  const heading = (
    <>
      <span>Game log</span>
      <span className="game-feed-count">{list.length}</span>
    </>
  );

  return (
    <section className="game-feed glass" aria-label="Game log">
      {wide ? (
        <div className="game-feed-toggle is-static">{heading}</div>
      ) : (
        <button
          type="button"
          className="game-feed-toggle"
          aria-expanded={expanded}
          onClick={() => setExpanded((value) => !value)}
        >
          {heading}
          <Icon name={expanded ? "chevron-up" : "chevron-down"} className="game-feed-chevron" />
        </button>
      )}
      {/* The banner floats over the top of the body, so showing or hiding it never changes the box height. */}
      <div className="game-feed-body">
        {banner && (
          <div key={banner.seq} className="game-feed-banner" role="status" data-type={banner.type}>
            {describeEvent(banner)}
          </div>
        )}
        {open && (
          <ol className="game-feed-list themed-scroll">
            {list.length === 0
              ? <li className="game-feed-empty">Moves and events will show up here.</li>
              : [...list].reverse().map((event) => (
                <li key={event.seq} className="game-feed-item" data-type={event.type}>
                  {describeEvent(event)}
                </li>
              ))}
          </ol>
        )}
      </div>
    </section>
  );
}
