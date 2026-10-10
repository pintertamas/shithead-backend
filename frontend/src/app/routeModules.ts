import type { ComponentType } from "react";

// Dynamic import() for every lazily loaded screen. routes.tsx and lib/prefetch.ts both go through these, so a
// prefetched module is the one the route later renders (the module cache makes repeat calls free).
export const routeLoaders = {
  games: () => import("./screens/Games"),
  leaderboard: () => import("./screens/Leaderboard"),
  profile: () => import("./screens/Profile"),
  config: () => import("./screens/GameConfig"),
  admin: () => import("./screens/Admin"),
  room: () => import("./screens/Room"),
  game: () => import("./screens/GameTableRoute"),
};

export type RouteKey = keyof typeof routeLoaders;

type Screen = ComponentType<any>;
const loadedScreens = new Map<RouteKey, Screen>();

/** Loads a screen's module and remembers its component, so later renders need no suspension. */
export function fetchScreen(key: RouteKey): Promise<Screen> {
  return routeLoaders[key]().then((module) => {
    const component = module.default as Screen;
    loadedScreens.set(key, component);
    return component;
  });
}

/** The screen's component if its module has already loaded (by a visit or a prefetch), otherwise undefined. */
export function readyScreen(key: RouteKey): Screen | undefined {
  return loadedScreens.get(key);
}
