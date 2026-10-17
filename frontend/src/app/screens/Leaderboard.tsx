import { useEffect, useRef, useState } from "react";
import { useAuth } from "../auth/useAuth";
import { LeaderboardEntry } from "../api/leaderboard";
import Pager from "../components/Pager";
import ErrorAlert from "../components/ErrorAlert";
import Icon from "../components/Icon";
import { RankingRowsSkeleton } from "../components/Skeleton";
import { useGlobalLeaderboardQuery } from "../data/queries";
import "../styles/leaderboard.css";
import "../styles/rankings.css";
import "../styles/leaderboard-bots.css";

/** Players shown per page of the leaderboard, on every screen size. */
const PAGE_SIZE = 10;

function TrophyIcon() {
  return (
    <span className="lobby-trophy" aria-hidden="true">
      <svg viewBox="0 0 40 40">
        <path d="M12 6h16v9c0 7-3 11-8 11s-8-4-8-11z" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinejoin="round" />
        <path d="M12 10H6v3c0 5 3 8 8 8M28 10h6v3c0 5-3 8-8 8M20 26v6m-7 3h14m-11-3h8" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" />
        <path d="m20 9 1.2 3 3.2.2-2.5 2 .8 3-2.7-1.7-2.7 1.7.8-3-2.5-2 3.2-.2z" fill="#d6b75e" />
      </svg>
    </span>
  );
}

function messageOf(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}

export default function Leaderboard() {
  const { token } = useAuth();
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const listRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  const globalQuery = useGlobalLeaderboardQuery(token);
  const globalData: LeaderboardEntry[] = globalQuery.data ?? [];
  const globalLoading = globalQuery.isPending;

  useEffect(() => {
    if (globalQuery.error) setError(messageOf(globalQuery.error, "Couldn't load the global leaderboard."));
  }, [globalQuery.error]);

  const rows = globalData;
  const loading = globalLoading;

  // Rank is the position in the full list, so filtering never renumbers players.
  const ranked = rows.map((entry, index) => ({ entry, rank: index + 1 }));
  const needle = query.trim().toLowerCase();
  const visible = needle
    ? ranked.filter(({ entry }) => entry.username.toLowerCase().includes(needle))
    : ranked;

  // Pagination runs over the filtered list. The page is clamped when the list shrinks (filter or data refresh).
  const totalPages = Math.max(1, Math.ceil(visible.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  useEffect(() => {
    if (page !== currentPage) setPage(currentPage);
  }, [page, currentPage]);
  const pageStart = (currentPage - 1) * PAGE_SIZE;
  const pagedVisible = visible.slice(pageStart, pageStart + PAGE_SIZE);
  const rangeStart = visible.length === 0 ? 0 : pageStart + 1;
  const rangeEnd = Math.min(pageStart + PAGE_SIZE, visible.length);

  const goToPage = (next: number) => {
    setPage(next);
    // The pager sits below the list, so bring the top of the list back into view after a page change.
    const list = listRef.current;
    if (list && list.getBoundingClientRect().top < 0) list.scrollIntoView({ block: "start" });
  };

  const changeQuery = (value: string) => {
    setQuery(value);
    setPage(1);
  };

  return (
    <div className="leaderboard-page fade-in">
      <div className="leaderboard-main">
        <ErrorAlert message={error} onDismiss={() => setError(null)} />
        <header className="lobby-welcome leaderboard-welcome">
          <div>
            <h1>Full leaderboard</h1>
            <p>See how every player ranks by ELO.</p>
          </div>
        </header>

        <section className="lobby-rankings" aria-labelledby="leaderboard-title">
          <div className="lobby-rankings-heading">
            <div className="lobby-rankings-title-wrap">
              <TrophyIcon />
              <div>
                <h2 id="leaderboard-title">Rankings</h2>
                <p>Every player and their current ELO.</p>
              </div>
            </div>
          </div>

          {loading ? <RankingRowsSkeleton rows={8} />
            : rows.length === 0 ? <p className="lobby-rankings-message">{error ? "Rankings couldn't be loaded." : "No rankings are available yet."}</p>
              : (
                <>
                  <div className="rankings-toolbar">
                    <div className="rankings-search">
                      <Icon name="search" size={16} className="rankings-search-icon" />
                      <input
                        type="search"
                        className="input rankings-search-input"
                        aria-label="Filter players by name"
                        placeholder="Search by name"
                        autoComplete="off"
                        spellCheck={false}
                        value={query}
                        onChange={(event) => changeQuery(event.target.value)}
                        onKeyDown={(event) => { if (event.key === "Escape") changeQuery(""); }}
                      />
                      {query && (
                        <button type="button" className="rankings-search-clear" aria-label="Clear search" onClick={() => changeQuery("")}>
                          <Icon name="close" size={16} />
                        </button>
                      )}
                    </div>
                    {needle && (
                      <span className="rankings-count" role="status" aria-live="polite">
                        {visible.length} of {rows.length}
                      </span>
                    )}
                  </div>

                  {visible.length === 0 ? (
                    <p className="lobby-rankings-message rankings-no-match" role="status">No players match “{query.trim()}”.</p>
                  ) : (
                    <div className="lobby-table-wrap" ref={listRef}>
                      <table className="lobby-rankings-table">
                        <thead><tr><th scope="col">#</th><th scope="col">Name</th><th scope="col">ELO</th></tr></thead>
                        <tbody>
                          {pagedVisible.map(({ entry, rank }) => (
                            <tr key={entry.userId}>
                              <td><span className={`lobby-rank${rank <= 3 ? ` top-${rank}` : ""}`}>{rank}</span></td>
                              <td>
                                {entry.username}
                                {entry.bot && (
                                  <>
                                    {" "}
                                    <span className="badge badge-bot rankings-bot-badge">Bot</span>
                                    {entry.games != null && <span className="rankings-bot-games">{entry.games} games</span>}
                                  </>
                                )}
                              </td>
                              <td>
                                {Math.round(entry.eloScore)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}

                  {visible.length > PAGE_SIZE && (
                    <Pager
                      label="Leaderboard pages"
                      page={currentPage}
                      totalPages={totalPages}
                      rangeStart={rangeStart}
                      rangeEnd={rangeEnd}
                      total={visible.length}
                      noun="players"
                      onPageChange={goToPage}
                    />
                  )}
                </>
              )}
        </section>
      </div>
    </div>
  );
}
