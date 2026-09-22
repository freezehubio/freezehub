/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],

    /*
     * `FZ-193`, closing `OI-47`: the suite failed three to eight tests on a developer
     * machine and passed every time in CI. The failures were always timeouts, always in
     * the heaviest files, and never quite the same set twice.
     *
     * It was not flakiness in the tests. It was starvation, and the suite was causing it.
     * Vitest defaults to one worker per core; on this 16-core machine, already running a
     * backend build and two other agent sessions, `npm run test` took the load average
     * from 16 to 77. Every worker then gets a fraction of a core, a test needing one
     * second of CPU takes five, and five seconds is the default timeout.
     *
     * Two settings, for two different reasons.
     */

    /*
     * Enough headroom that somebody else's build does not read as a failing test.
     *
     * A timeout catches a hang; it is not a performance budget. Fifteen seconds still
     * reports a genuinely stuck test promptly and is far outside anything healthy here —
     * on an idle machine the slowest file averages well under a second per test.
     */
    testTimeout: 15_000,
    hookTimeout: 15_000,

    /*
     * Do not take the whole machine.
     *
     * Half the cores is still parallel and still fast, and it stops this suite being the
     * reason another build times out. On a shared developer machine that is a courtesy;
     * with several sessions running at once it is what keeps any of the results
     * meaningful.
     */
    maxWorkers: '50%',

    env: {
      // Deliberately NOT UTC. A datetime-local value that is submitted without being
      // converted to UTC looks perfectly correct on a UTC machine and is silently wrong
      // by the offset everywhere else — running the suite at UTC-5 is what makes that
      // class of bug fail a test instead of shipping (01-domain.md invariants 9 and 10).
      TZ: 'America/Bogota',
    },
  },
})
