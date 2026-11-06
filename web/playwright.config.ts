import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e", fullyParallel: false, workers: 1,
  use: { baseURL: "http://127.0.0.1:38090", trace: "retain-on-failure" },
  webServer: { command: "python3 tools/e2e-server.py", url: "http://127.0.0.1:38090/api/health", reuseExistingServer: false },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
