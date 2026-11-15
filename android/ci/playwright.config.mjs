import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  testMatch: /.*\.spec\.mjs/,
  timeout: 60_000,
  retries: 1,
  outputDir: "test-results",
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    ...devices["Pixel 7"],
    baseURL: process.env.MEALIE_URL || "http://localhost:9000",
    trace: "retain-on-failure",
    // הרצה מקומית עם Chromium שכבר מותקן (ב-CI מותקן הדפדפן של Playwright)
    launchOptions: process.env.PW_EXE ? { executablePath: process.env.PW_EXE } : {},
  },
});
