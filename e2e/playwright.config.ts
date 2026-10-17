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
  // Every spec arranges its own data through the screens on a sheet id of its own and reads the
  // audit file filtered by that id, so specs never share data. workers:1 keeps the suite serial
  // against the one shared app, so a failure stays attributable to one spec.
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Headless Chromium needs its own sandbox off and
        // shared memory in /tmp, or renderer/GPU child processes crash. Harmless elsewhere too.
        launchOptions: { args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] },
      },
    },
  ],
});
