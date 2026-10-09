import { ReactNode, useEffect, useRef, useState } from "react";
import { PersistQueryClientProvider } from "@tanstack/react-query-persist-client";
import { subscribeAccountBlocked } from "../auth/accountBlocked";
import { clearAppCache, persistOptions, queryClient, syncCacheOwner, writeCacheOwner } from "./queryClient";
import { userKeyFromToken } from "./userKey";

type Props = { token: string; children: ReactNode };

export default function AppDataProvider({ token, children }: Props) {
  const sub = userKeyFromToken(token);

  // Runs on first render, before the persister restores, so another user's cached data is never shown.
  useState(() => syncCacheOwner(sub));

  const lastSub = useRef(sub);
  useEffect(() => {
    if (lastSub.current === sub) return;
    lastSub.current = sub;
    clearAppCache();
    writeCacheOwner(sub);
  }, [sub]);

  useEffect(() => subscribeAccountBlocked(clearAppCache), []);

  return (
    <PersistQueryClientProvider client={queryClient} persistOptions={persistOptions}>
      {children}
    </PersistQueryClientProvider>
  );
}
