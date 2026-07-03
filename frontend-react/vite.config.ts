/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Mirrors frontend-ui/proxy.conf.json — same-origin through the gateway, zero CORS.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // `@` → src, matching tsconfig paths (shadcn/ui components import via this alias).
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    host: '127.0.0.1', // bind IPv4 so Playwright's 127.0.0.1 health-check + curl reach it (Windows dual-stack)
    port: 4300,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', secure: false, changeOrigin: true },
      '/ws': { target: 'http://127.0.0.1:8080', secure: false, ws: true },
    },
  },
  build: {
    outDir: 'dist',
    // Bucket the big vendor libs into their own cached chunks so the entry `index` chunk stays lean
    // (heavy libs become separately-cacheable chunks instead of bloating the initial payload). The
    // ECharts-bearing pages are already React.lazy, so vendor-echarts loads only with those routes.
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          // tslib's TS runtime helpers are shared between zrender (echarts) and radix-ui (eager); keep
          // them in the common vendor chunk so bucketing echarts can't drag the 1 MB chart lib into the
          // entry's static graph (the entry uses tslib via radix scroll-lock).
          if (id.includes('/tslib/')) return 'vendor-react';
          if (id.includes('echarts') || id.includes('zrender')) return 'vendor-echarts';
          if (id.includes('lightweight-charts')) return 'vendor-lwc';
          if (id.includes('@tanstack')) return 'vendor-tanstack';
          if (id.includes('react-router') || id.includes('/react-dom/') || id.includes('/react/')) {
            return 'vendor-react';
          }
          if (id.includes('/motion/') || id.includes('/framer-motion/')) return 'vendor-motion';
          return undefined;
        },
      },
    },
    // vendor-echarts is a single genuinely-irreducible ~1 MB lib chunk (loaded lazily with chart
    // routes, never in the entry payload), so lift the warning ceiling enough to silence just it.
    chunkSizeWarningLimit: 1100,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    // Coverage floor (audit frontend-vitest-no-coverage-floor): the pure calculation (core/) and
    // API-fold (api/) layers are the FE's money math — hold their line coverage so it can't erode.
    // Pages ride the e2e/axe shard instead of chasing RTL specs for all of them.
    coverage: {
      provider: 'v8' as const,
      include: ['src/core/**', 'src/api/**'],
      thresholds: { lines: 50 },
      reporter: ['text-summary'],
    },
  },
});
