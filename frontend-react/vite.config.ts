/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Mirrors frontend-ui/proxy.conf.json — same-origin through the gateway, zero CORS.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
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
