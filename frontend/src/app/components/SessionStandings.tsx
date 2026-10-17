import { useMemo } from "react";
import type { LeaderboardEntry } from "../api/leaderboard";
import { useSessionLeaderboardQuery } from "../data/queries";
import "../styles/elo-change.css";
import "../styles/leaderboard-bots.css";

/**
 * Shows how a player's rating moved in the session's game: a green up triangle with "+16", a red down triangle
 * with "-16", or a grey dash for no change, plus the rating it started from. Both values are rounded the same way
 * as the displayed rating, so the delta always matches the numbers shown.
 */
export function EloChangeIndicator({ before, after }: { before: number; after: number }) {
  const from = Math.round(before);
  const to = Math.round(after);
  const delta = to - from;
  const direction = delta > 0 ? "up" : delta < 0 ? "down" : "flat";
  const signed = delta > 0 ? `+${delta}` : delta < 0 ? `${delta}` : "0";
  const label = delta === 0
    ? `Rating unchanged, ${to}`
    : `Rating ${direction} ${Math.abs(delta)}, from ${from} to ${to}`;
  return (
    <>
      <span className={`elo-change elo-change-${direction}`} role="img" aria-label={label}>
        {direction === "flat" ? (
          <svg className="elo-change-icon" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
            <rect x="1" y="4" width="8" height="2" />
          </svg>
        ) : (
          <svg className="elo-change-icon" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
            <path d={direction === "up" ? "M5 1 L9.5 8.5 H0.5 Z" : "M5 9 L9.5 1.5 H0.5 Z"} />
          </svg>
        )}
        {signed}
      </span>
      {delta !== 0 && <span className="elo-change-from" aria-hidden="true">from {from}</span>}
    </>
  );
}

/** Points won or lost in the session's game, from the same rounded ratings as EloChangeIndicator. Null when none recorded. */
export function sessionPoints(entry: LeaderboardEntry): number | null {
  if (entry.eloBefore == null || entry.eloAfter == null) return null;
  return Math.round(entry.eloAfter) - Math.round(entry.eloBefore);
}

/** Session rows may carry `shithead` (the seat that lost the game). A missing field counts as false. */
export function lostGame(entry: LeaderboardEntry): boolean {
  return (entry as LeaderboardEntry & { shithead?: boolean }).shithead === true;
}

/**
 * Session rows ranked by points, highest first. Rows without a recorded change go after all ranked rows. Ties and
 * unrecorded rows keep seat order: the sort is stable and runs on a copy, so the query data is not mutated.
 */
export function rankSessionRows(rows: LeaderboardEntry[]): LeaderboardEntry[] {
  return [...rows].sort((a, b) => {
    const pointsA = sessionPoints(a);
    const pointsB = sessionPoints(b);
    if (pointsA === null || pointsB === null) {
      if (pointsA === pointsB) return 0;
      return pointsA === null ? 1 : -1;
    }
    return pointsB - pointsA;
  });
}

/** Final standings of one game: rank, name, rating with its change, and the shithead marker. */
export default function SessionStandings({ token, sessionId }: { token: string | null | undefined; sessionId: string }) {
  const query = useSessionLeaderboardQuery(token ?? "", sessionId);
  const rows = useMemo(() => rankSessionRows(query.data ?? []), [query.data]);

  if (query.isPending) return <p className="standings-message" role="status">Loading standings...</p>;
  if (query.error) {
    return <p className="standings-message" role="alert">
      {query.error instanceof Error ? query.error.message : "Couldn't load the standings."}
    </p>;
  }
  if (rows.length === 0) return <p className="standings-message">No standings are available yet.</p>;

  return (
    <ol className="standings-list" aria-label="Final standings">
      {rows.map((entry, index) => {
        const rank = index + 1;
        return (
          <li key={entry.userId} className="standings-row">
            <span className={`lobby-rank${rank <= 3 ? ` top-${rank}` : ""}`}>{rank}</span>
            <span className="standings-name">
              {entry.username}
              {entry.bot && <> <span className="badge badge-bot rankings-bot-badge">Bot</span></>}
              {lostGame(entry) && (
                <>
                  {" "}
                  <span aria-hidden="true">💩</span>
                  <span className="standings-sr">Lost the game</span>
                </>
              )}
            </span>
            <span className="standings-elo">
              {entry.eloBefore != null && entry.eloAfter != null ? (
                <span className="elo-cell">
                  <span className="elo-value">{Math.round(entry.eloScore)}</span>
                  <EloChangeIndicator before={entry.eloBefore} after={entry.eloAfter} />
                </span>
              ) : Math.round(entry.eloScore)}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
