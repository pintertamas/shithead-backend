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

// Placeholder for a lazily loaded screen. "menu" matches the lobby content column; "bare" is for full-page routes.
export function RouteSkeleton({ variant = "menu" }: { variant?: "menu" | "bare" }) {
  return (
    <div className={variant === "menu" ? "lobby-content route-skeleton" : "route-skeleton route-skeleton-bare"} role="status">
      <span className="visually-hidden">Loading…</span>
      <div className="route-skeleton-blocks" aria-hidden="true">
        <Skeleton height={34} width="40%" />
        <Skeleton height={16} width="65%" />
        <Skeleton height={156} />
      </div>
    </div>
  );
}

// Same markup and classes as the loaded user table and pager in screens/Admin.tsx, so the placeholder takes the same
// space: one card or row per user on the page, plus the pager row.
export function AdminRowsSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <>
      <div className="admin-table-wrap glass skeleton-admin" role="status">
        <span className="visually-hidden">Loading users…</span>
        <table className="admin-table" aria-hidden="true">
          <thead>
            <tr>
              <th scope="col">User</th>
              <th scope="col"><span className="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: rows }, (_, index) => (
              <tr className="admin-row" key={index}>
                <td data-label="User">
                  <span className="admin-username"><Skeleton height={16} width="60%" /></span>
                  <span className="admin-email"><Skeleton height={14} width="75%" /></span>
                  <span className="admin-userid"><Skeleton height={12} width="85%" /></span>
                  <span className="admin-meta"><Skeleton height={16} width={110} /></span>
                </td>
                <td data-label="Action" className="admin-action-cell"><Skeleton height={32} width={72} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="admin-pager skeleton-admin-pager" aria-hidden="true">
        <Skeleton className="skeleton-pager-button" />
        <div className="admin-pager-status">
          <Skeleton height={16} width={90} />
          <Skeleton height={13} width={140} />
        </div>
        <Skeleton className="skeleton-pager-button" />
      </div>
    </>
  );
}
