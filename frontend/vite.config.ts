import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

function normalizeBasePath(value: string): string {
  if (!value || value === "/") return "/";
  const withLeadingSlash = value.startsWith("/") ? value : `/${value}`;
  return withLeadingSlash.endsWith("/") ? withLeadingSlash : `${withLeadingSlash}/`;
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const base = normalizeBasePath(env.VITE_BASE_PATH || "/");

  return {
    base,
    plugins: [react()],
    build: {
      rollupOptions: {
        output: {
          // Stable vendor and query-client chunks, so app code changes do not invalidate them. livekit-client keeps
          // its own lazy chunk (returning undefined leaves it to Rollup).
          manualChunks(id) {
            if (!id.includes("node_modules")) return undefined;
            if (/[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler|@remix-run[\\/]router)[\\/]/.test(id)) {
              return "vendor";
            }
            if (/[\\/]node_modules[\\/]@tanstack[\\/]/.test(id)) return "tanstack";
            return undefined;
          }
        }
      }
    },
    server: {
      port: 5173
    }
  };
});
