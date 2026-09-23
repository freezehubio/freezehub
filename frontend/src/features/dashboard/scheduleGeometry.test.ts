import { describe, expect, it } from 'vitest'
import type { RestrictionLevel, RestrictionStatus, RestrictionSummary } from '../../types/api'
import {
  TIMELINE_DAYS,
  barFor,
  clearRunways,
  formatDuration,
  positionOf,
  timelineWindow,
} from './scheduleGeometry'

/**
 * A fixed "now" with a fixed zone, because every assertion here is about where something
 * lands relative to local midnight. Left to the machine's clock these would pass in one
 * time zone and fail in another, which is the bug this module is most likely to have.
 */
const NOW = new Date('2026-09-23T16:25:00Z')

function restriction(
  startsAt: string,
  endsAt: string,
  level: RestrictionLevel = 'HARD_FREEZE',
  status: RestrictionStatus = 'SCHEDULED',
): RestrictionSummary {
  return {
    id: Math.floor(Math.random() * 1e6),
    name: 'A restriction',
    reason: 'because',
    type: 'DEPLOYMENT_FREEZE',
    level,
    status,
    startsAt,
    endsAt,
    createdBy: 1,
    createdAt: startsAt,
    updatedAt: startsAt,
  }
}

/** Midnight local on the day of NOW, as an ISO string offset by whole days. */
function dayOffset(days: number, hours = 0): string {
  const base = timelineWindow(NOW).start
  return new Date(base + days * 86_400_000 + hours * 3_600_000).toISOString()
}

describe('timelineWindow', () => {
  it('opens at local midnight today and runs a fortnight', () => {
    const window = timelineWindow(NOW)
    const start = new Date(window.start)

    expect(start.getHours()).toBe(0)
    expect(start.getMinutes()).toBe(0)
    expect(start.getDate()).toBe(NOW.getDate())
    expect(window.days).toHaveLength(TIMELINE_DAYS)
  })

  it('gives every column a local midnight, so a DST change does not shift the scale', () => {
    // Late October crosses the European change and early November the American one; both
    // are covered by a fortnight from this date.
    const window = timelineWindow(new Date('2026-10-20T12:00:00Z'))
    for (const day of window.days) {
      expect(day.getHours()).toBe(0)
    }
  })

  it('puts now inside the first day rather than on the left edge', () => {
    const window = timelineWindow(NOW)
    const at = positionOf(NOW.getTime(), window)

    expect(at).toBeGreaterThan(0)
    expect(at).toBeLessThan(100 / TIMELINE_DAYS)
  })
})

describe('barFor', () => {
  const window = timelineWindow(NOW)

  it('places a whole window inside the fortnight', () => {
    const bar = barFor(restriction(dayOffset(2), dayOffset(4)), window)

    expect(bar).not.toBeNull()
    expect(bar!.left).toBeCloseTo((2 / 14) * 100, 5)
    expect(bar!.width).toBeCloseTo((2 / 14) * 100, 5)
    expect(bar!.fromBefore).toBe(false)
    expect(bar!.runsOn).toBe(false)
  })

  it('clips one that began earlier, and says that it did', () => {
    const bar = barFor(restriction(dayOffset(-3), dayOffset(2)), window)

    expect(bar!.left).toBe(0)
    expect(bar!.width).toBeCloseTo((2 / 14) * 100, 5)
    expect(bar!.fromBefore).toBe(true)
    // The label is the restriction's real length, not the part that fits.
    expect(bar!.duration).toBe('5 days')
  })

  it('clips one that runs past the fortnight, and says that it does', () => {
    const bar = barFor(restriction(dayOffset(12), dayOffset(30)), window)

    expect(bar!.runsOn).toBe(true)
    expect(bar!.left + bar!.width).toBeCloseTo(100, 5)
  })

  it('returns null for one entirely beyond the window, rather than a zero-width bar', () => {
    expect(barFor(restriction(dayOffset(20), dayOffset(25)), window)).toBeNull()
  })

  it('returns null for one entirely before it', () => {
    expect(barFor(restriction(dayOffset(-9), dayOffset(-2)), window)).toBeNull()
  })

  it('gives a very short freeze a bar somebody can see', () => {
    // One hour is 0.3% of a fortnight — a hairline, and the windows most easily forgotten
    // would be the ones the chart hid.
    const bar = barFor(restriction(dayOffset(5), dayOffset(5, 1)), window)

    expect(bar!.width).toBeGreaterThanOrEqual(1.5)
    expect(bar!.duration).toBe('1h')
  })

  it('keeps a floored bar inside the chart instead of overhanging its right edge', () => {
    // An overhang would read as "continues past the fortnight", which is a different claim.
    const bar = barFor(restriction(dayOffset(14, -1), dayOffset(14)), window)

    expect(bar!.left + bar!.width).toBeLessThanOrEqual(100.0001)
    expect(bar!.runsOn).toBe(false)
  })

  it('ignores a restriction whose dates do not parse', () => {
    expect(barFor(restriction('not-a-date', dayOffset(2)), window)).toBeNull()
  })
})

describe('clearRunways', () => {
  const window = timelineWindow(NOW)

  it('is the whole rest of the fortnight when nothing is scheduled', () => {
    const runways = clearRunways([], window, NOW)

    expect(runways).toHaveLength(1)
    expect(runways[0].start).toBe(NOW.getTime())
    expect(runways[0].left + runways[0].width).toBeCloseTo(100, 5)
  })

  it('does not count an advisory as blocking', () => {
    // An advisory refuses nothing (01-domain.md), so runway it overlaps is still runway.
    // Counting it would make the one figure a release is planned against pessimistic.
    const runways = clearRunways(
      [restriction(dayOffset(1), dayOffset(6), 'ADVISORY')],
      window,
      NOW,
    )

    expect(runways).toHaveLength(1)
    expect(runways[0].start).toBe(NOW.getTime())
  })

  it('finds the gap between two freezes', () => {
    const runways = clearRunways(
      [restriction(dayOffset(0), dayOffset(3)), restriction(dayOffset(6), dayOffset(9))],
      window,
      NOW,
    )

    expect(runways).toHaveLength(2)
    expect(runways[0].duration).toBe('3 days')
    expect(runways[1].duration).toBe('5 days')
  })

  it('merges overlapping freezes rather than inventing a gap between them', () => {
    const runways = clearRunways(
      [restriction(dayOffset(0), dayOffset(5)), restriction(dayOffset(2), dayOffset(8))],
      window,
      NOW,
    )

    expect(runways).toHaveLength(1)
    expect(runways[0].duration).toBe('6 days')
  })

  it('reports none when a freeze covers the whole fortnight', () => {
    expect(clearRunways([restriction(dayOffset(-1), dayOffset(20))], window, NOW)).toEqual([])
  })

  it('never offers runway in the past', () => {
    const runways = clearRunways([restriction(dayOffset(10), dayOffset(12))], window, NOW)

    expect(runways[0].start).toBe(NOW.getTime())
    expect(runways.every((runway) => runway.start >= NOW.getTime())).toBe(true)
  })

  it('discards a gap too short to be runway', () => {
    // Two windows meeting a minute apart is an artefact, not an opportunity to deploy.
    const runways = clearRunways(
      [
        restriction(dayOffset(1), dayOffset(5)),
        restriction(new Date(timelineWindow(NOW).start + 5 * 86_400_000 + 60_000).toISOString(),
          dayOffset(9)),
      ],
      window,
      NOW,
    )

    expect(runways.some((runway) => runway.duration === '1m')).toBe(false)
  })
})

describe('formatDuration', () => {
  it.each([
    [30 * 60_000, '30m'],
    [3_600_000, '1h'],
    [8 * 3_600_000, '8h'],
    [86_400_000, '1 day'],
    [2 * 86_400_000 + 22 * 3_600_000, '2d 22h'],
    [7 * 86_400_000, '7 days'],
  ])('says %i ms as %s', (ms, expected) => {
    expect(formatDuration(ms)).toBe(expected)
  })

  it('never says zero', () => {
    expect(formatDuration(1_000)).toBe('1m')
  })
})
