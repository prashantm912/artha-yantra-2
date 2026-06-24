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
  build: { outDir: 'dist' },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
});
