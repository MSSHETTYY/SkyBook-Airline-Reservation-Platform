import { defineConfig, devices } from '@playwright/test';

/**
 * E2E against the production bundle built in mock mode (`vite build --mode mock`): the real UI code,
 * with MSW standing in for the gateway. The same specs run against the full Docker stack by setting
 * E2E_BASE_URL (and VITE_AUTH_MODE=oidc in that build), see docs/runbook.
 */
const externalBaseUrl = process.env.E2E_BASE_URL;
const port = 4173;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : 1,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: externalBaseUrl ?? `http://localhost:${port}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  // Journeys run on all three engines plus a phone viewport. axe scans the DOM/ARIA tree, which is
  // engine-independent for this markup, so accessibility runs on Chromium (desktop + mobile) only;
  // under memory pressure Firefox's content process also crashed inside axe-core (ADR-0017).
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] }, testIgnore: /accessibility/ },
    { name: 'webkit', use: { ...devices['Desktop Safari'] }, testIgnore: /accessibility/ },
    { name: 'mobile', use: { ...devices['Pixel 7'] }, testMatch: /accessibility|booking/ },
  ],
  webServer: externalBaseUrl
    ? undefined
    : {
        command: `npm run build:mock && npx vite preview --mode mock --port ${port} --strictPort`,
        url: `http://localhost:${port}`,
        reuseExistingServer: !process.env.CI,
        timeout: 180_000,
      },
});
