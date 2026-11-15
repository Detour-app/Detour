import { defineConfig } from "vitest/config";

// Served by the API under /planner (backend/Detour/Detour.Api/PlannerHosting.cs), so every
// asset URL carries that prefix. The dev server uses the same base, which is what makes
// http://localhost:5173/planner/ match the dev realm's detour-web redirect URI.
//
// Dev: `npm run dev` with the docker/dev stack up and the API running on :7500. The planner
// only ever calls its own origin, as it does behind nginx in production, so the dev server
// proxies those paths to where the dev stack has them. Override with the env vars below when
// they live elsewhere (another host, the dev rig).
const api = process.env.DETOUR_DEV_API ?? "http://localhost:7500";
const routing = process.env.DETOUR_DEV_ROUTING ?? "http://localhost:7510";

export default defineConfig({
  base: "/planner/",
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": api,
      "/route": routing,
      "/info": routing,
    },
  },
  build: {
    outDir: "dist",
    // MapLibre alone is past Vite's 500 kB warning; one chunk is fine for one page.
    chunkSizeWarningLimit: 2000,
  },
  // MapLibre's worker is an ES module that imports a shared chunk (src/map.ts).
  worker: { format: "es" },
  test: {
    include: ["src/**/*.test.ts"],
  },
});
