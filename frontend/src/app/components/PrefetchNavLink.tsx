import { NavLink, type NavLinkProps } from "react-router-dom";
import { usePrefetch } from "../lib/prefetch";
import type { RouteKey } from "../routeModules";

type Props = NavLinkProps & { prefetch: RouteKey };

// A NavLink that starts loading its screen on hover, focus or touch start (see lib/prefetch.ts).
export default function PrefetchNavLink({ prefetch, ...linkProps }: Props) {
  const { ref, ...intent } = usePrefetch<HTMLAnchorElement>([prefetch]);
  return <NavLink ref={ref} {...linkProps} {...intent} />;
}
