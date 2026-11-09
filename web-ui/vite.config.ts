/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

/**
 * Dev server proxies /api to the gateway, so the browser talks to one origin and no CORS
 * pre-flight is needed locally. `--mode mock` swaps the backend for MSW (see src/mocks).
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: env.SKYBOOK_GATEWAY_URL ?? 'http://localhost:8080', changeOrigin: true },
      },
    },
    preview: { port: 4173, strictPort: true },
    build: {
      sourcemap: true,
      rollupOptions: {
        output: {
          manualChunks: {
            react: ['react', 'react-dom', 'react-router'],
            mui: ['@mui/material', '@emotion/react', '@emotion/styled'],
            query: ['@tanstack/react-query'],
          },
        },
      },
    },
    test: {
      globals: true,
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
      css: false,
      testTimeout: 15_000,
      // Small machines (CI runners, 8 GB laptops): two workers; Node 26's own localStorage clashes with jsdom's.
      pool: 'forks',
      maxWorkers: 2,
      minWorkers: 1,
      poolOptions: { forks: { execArgv: ['--no-experimental-webstorage'] } },
      coverage: {
        provider: 'v8',
        reporter: ['text-summary', 'html', 'lcov'],
        include: ['src/**/*.{ts,tsx}'],
        exclude: [
          'src/api/generated/**',
          'src/main.tsx',
          'src/mocks/browser.ts',
          'src/test/**',
          'src/**/*.test.{ts,tsx}',
          'src/vite-env.d.ts',
        ],
        thresholds: { lines: 80, statements: 80, functions: 75, branches: 70 },
      },
    },
  };
});
