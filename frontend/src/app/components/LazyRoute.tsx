import { Component, lazy, type ComponentType, type ReactNode, Suspense, useState } from "react";
import { loadRoute } from "../lib/chunkReload";
import { fetchScreen, readyScreen, type RouteKey } from "../routeModules";
import { RouteSkeleton } from "./Skeleton";

type BoundaryProps = { children: ReactNode };
type BoundaryState = { failed: boolean };

// A chunk that still fails after the one automatic reload (or a render error) shows a retry instead of a blank app.
class RouteErrorBoundary extends Component<BoundaryProps, BoundaryState> {
  state: BoundaryState = { failed: false };

  static getDerivedStateFromError(): BoundaryState {
    return { failed: true };
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="lobby-content" role="alert">
        <h1>This page could not load.</h1>
        <p>Check your connection and try again.</p>
        <button className="button" onClick={() => window.location.reload()}>Reload</button>
      </div>
    );
  }
}

type Props = { children: ReactNode; variant?: "menu" | "bare" };

// The fallback uses the same skeleton blocks as the menu screens, so the page does not jump when the chunk arrives.
export default function LazyRoute({ children, variant = "menu" }: Props) {
  return (
    <RouteErrorBoundary>
      <Suspense fallback={<RouteSkeleton variant={variant} />}>{children}</Suspense>
    </RouteErrorBoundary>
  );
}

/**
 * A lazily loaded screen. React.lazy always suspends on its first render, even for a module that is already cached
 * (for example after a prefetch), which would flash the skeleton. So a screen that is already loaded renders directly.
 * The choice is made once per mount so the element type never flips and the screen is not remounted.
 */
export function routeScreen(key: RouteKey): ComponentType {
  const Lazy = lazy(() => loadRoute(() => fetchScreen(key)).then((component) => ({ default: component })));
  return function RouteScreen() {
    const [Ready] = useState(() => readyScreen(key));
    const Target: ComponentType = Ready ?? Lazy;
    return <Target />;
  };
}
