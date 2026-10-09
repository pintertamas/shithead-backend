import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { joinGame } from "../api/game";
import { OpenGame } from "../api/games";
import { useAuth } from "../auth/useAuth";
import ErrorAlert from "../components/ErrorAlert";
import { GameCardsSkeleton } from "../components/Skeleton";
import { invalidateGames, useOpenGamesQuery } from "../data/queries";
import "../styles/games.css";

export default function Games() {
  const navigate = useNavigate();
  const { token } = useAuth();
  // Cached rows paint immediately; the query refreshes in the background and polls every 5 s while mounted and visible.
  const gamesQuery = useOpenGamesQuery(token);
  const games: OpenGame[] = gamesQuery.data ?? [];
  const loading = gamesQuery.isPending;
  const [error, setError] = useState<string | null>(null);
  const [joiningId, setJoiningId] = useState<string | null>(null);

  useEffect(() => {
    if (gamesQuery.isError) setError(messageOf(gamesQuery.error, "Couldn't load open games."));
    else if (gamesQuery.isSuccess) setError(null);
  }, [gamesQuery.isError, gamesQuery.isSuccess, gamesQuery.error]);

  const handleJoin = async (sessionId: string) => {
    setError(null);
    setJoiningId(sessionId);
    try {
      await joinGame(token, sessionId);
      void invalidateGames(token);
      navigate(`/room/${sessionId}`);
    } catch (cause) {
      setError(messageOf(cause, "Couldn't join this game."));
      setJoiningId(null);
    }
  };

  return (
    <div className="games-page fade-in">
      <ErrorAlert message={error} onDismiss={() => setError(null)} />
      <header className="lobby-welcome">
        <div>
          <h1>Browse games</h1>
          <p>Open lobbies you can join. The list refreshes every few seconds.</p>
        </div>
      </header>

      {loading ? (
        <GameCardsSkeleton count={3} />
      ) : games.length === 0 ? (
        <div className="glass card games-empty">
          <h2 className="title">No open games right now</h2>
          <p>Nobody is waiting for players. Create your own game from Home and share the code with friends.</p>
          <button className="button" type="button" onClick={() => navigate("/lobby")}>Create a game</button>
        </div>
      ) : (
        <ul className="games-grid" aria-label="Open games">
          {games.map((game) => (
            <GameCard
              key={game.sessionId}
              game={game}
              joining={joiningId === game.sessionId}
              joinBlocked={joiningId !== null}
              onJoin={() => handleJoin(game.sessionId)}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

type GameCardProps = {
  game: OpenGame;
  joining: boolean;
  joinBlocked: boolean;
  onJoin: () => void;
};

function GameCard({ game, joining, joinBlocked, onJoin }: GameCardProps) {
  const inProgress = game.status === "in_progress";
  const full = game.playerCount >= game.maxPlayers;
  const created = formatCreated(game.createdAt);

  return (
    <li className="glass card game-card">
      <div className="game-card-header">
        <h2 className="game-card-owner">Game by {game.ownerName}</h2>
        {inProgress
          ? <span className="badge game-badge-muted" aria-disabled="true" title="Games that have started can't be joined">In progress</span>
          : <span className="badge">Waiting</span>}
      </div>
      <p className="game-card-meta">
        <span>{game.playerCount} / {game.maxPlayers} players</span>
        <span>{game.decksCount} {game.decksCount === 1 ? "deck" : "decks"}</span>
        {created && <span>Created {created}</span>}
      </p>
      {!inProgress && (
        <div className="game-card-footer">
          <button
            className="button"
            type="button"
            disabled={full || joinBlocked}
            onClick={onJoin}
            aria-label={`Join game by ${game.ownerName}`}
          >
            {joining ? "Joining…" : full ? "Full" : "Join"}
          </button>
        </div>
      )}
    </li>
  );
}

function formatCreated(createdAt: string | null): string {
  if (!createdAt) return "";
  const date = new Date(createdAt);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function messageOf(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}
