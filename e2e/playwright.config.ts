// Playwright config. Tests bind to data-testid only and assert through the UI. The app is already
// running (started by bin/e2e), so no webServer here.
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './specs',
  use: {
    baseURL: process.env.BASE_URL ?? `http://localhost:${process.env.PORT ?? 3000}`,
    // The contract attribute is data-testid — Playwright's default, so no testIdAttribute override
    // needed. Strict awaiting on data-testid presence is the flake guard.
  },
  // Serial + fresh reset+seed per spec (see support/seed.ts) keeps specs isolated. workers:1 is
  // REQUIRED: every spec drives ONE shared app + in-memory H2 through /__test__/reset+seed, so the
  // whole suite must run serially. `fullyParallel:false` alone only serialises tests WITHIN a file
  // — Playwright still runs different spec FILES on parallel workers by default, and those would
  // stomp on each other's seed data (false cross-file failures). One worker = fully serial.
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Headless Chromium in a restricted/confined environment needs its own sandbox off and
        // shared memory in /tmp, or renderer/GPU child processes crash. Harmless elsewhere too.
        launchOptions: { args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] },
      },
    },
  ],
});
