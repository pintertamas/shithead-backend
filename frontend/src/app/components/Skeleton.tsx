import "../styles/skeleton.css";

type BoxProps = { width?: number | string; height?: number | string; className?: string };

// A single shimmering placeholder block. Decorative, so it is hidden from assistive tech.
export default function Skeleton({ width, height, className }: BoxProps) {
  return <span className={`skeleton${className ? ` ${className}` : ""}`} style={{ width, height }} aria-hidden="true" />;
}

export function RankingRowsSkeleton({ rows = 3, label = "Loading rankings…" }: { rows?: number; label?: string }) {
  return (
    <div className="skeleton-rows" role="status">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: rows }, (_, index) => (
        <div className="skeleton-row" key={index}>
          <Skeleton width={28} height={18} />
          <Skeleton className="skeleton-grow" height={18} />
          <Skeleton width={48} height={18} />
        </div>
      ))}
    </div>
  );
}

export function GameCardsSkeleton({ count = 3 }: { count?: number }) {
  return (
    <div className="skeleton-games" role="status">
      <span className="visually-hidden">Loading games…</span>
      {Array.from({ length: count }, (_, index) => (
        <div className="glass card game-card skeleton-card" key={index} aria-hidden="true">
          <Skeleton height={20} width="60%" />
          <Skeleton height={14} width="40%" />
          <Skeleton height={36} width={90} className="skeleton-cta" />
        </div>
      ))}
    </div>
  );
}

export function AdminRowsSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div className="admin-table-wrap glass skeleton-admin" role="status">
      <span className="visually-hidden">Loading users…</span>
      {Array.from({ length: rows }, (_, index) => (
        <div className="skeleton-admin-row" key={index} aria-hidden="true">
          <Skeleton height={16} width="45%" />
          <Skeleton height={16} width={48} />
          <Skeleton height={24} width={72} />
          <Skeleton height={32} width={84} />
        </div>
      ))}
    </div>
  );
}
