import type { RestrictionSummary } from '../../types/api'

/**
 * The geometry behind the schedule timeline (`FZ-191`, direction `1b`).
 *
 * Named `scheduleGeometry` and not `scheduleTimeline` because `ScheduleTimeline.tsx` sits
 * beside it: two files differing only in the case of their first letter resolve to one file
 * on a case-insensitive filesystem, which is every macOS checkout here. TypeScript picks
 * whichever it saw first and the import fails with no useful message.
 *
 * Kept out of the component and free of React for the same reason as `dashboardSentences`:
 * these are the only places the view asserts something it was not told — *this freeze covers
 * these days*, *you have two clear days on Thursday* — and an assertion like that is worse
 * than no assertion if it is wrong. Pure functions, so the awkward cases can be tested
 * directly: a freeze that started before the window, one that ends after it, two that
 * overlap, one an hour long, and a window with nothing in it at all.
 */

/** A fortnight. Long enough to plan a release around, short enough that a day is legible. */
export const TIMELINE_DAYS = 14

const MS_PER_HOUR = 3_600_000
const MS_PER_DAY = 86_400_000

/**
 * A bar narrower than this is a line nobody can see or point at. A two-hour freeze is
 * 0.6% of a fortnight, so without a floor the shortest windows — the ones most easily
 * forgotten — would be the ones the view hides.
 */
const MIN_BAR_PERCENT = 1.5

/** Ignore a gap shorter than this: it is an artefact of two windows meeting, not runway. */
const MIN_RUNWAY_MS = MS_PER_HOUR

export interface TimelineWindow {
  /** Local midnight today. */
  start: number
  end: number
  /** One local midnight per column, for the axis. */
  days: Date[]
}

export interface TimelineBar {
  restriction: RestrictionSummary
  /** Percent of the window's width. */
  left: number
  width: number
  /** It began before this window opened, so the bar is cut off at the left. */
  fromBefore: boolean
  /** It runs past this window, so the bar is cut off at the right. */
  runsOn: boolean
  /** How long the restriction really is — not how much of it this window shows. */
  duration: string
}

export interface TimelineRunway {
  left: number
  width: number
  start: number
  duration: string
}

/**
 * The window the timeline draws: local midnight today, plus a fortnight.
 *
 * It starts at midnight rather than at `now` so the columns are whole days that can carry a
 * date. That puts *now* a little way into the first column rather than on the left edge,
 * which is why the view draws a rule for it.
 */
export function timelineWindow(now: Date = new Date()): TimelineWindow {
  const start = new Date(now)
  start.setHours(0, 0, 0, 0)

  const days: Date[] = []
  for (let i = 0; i < TIMELINE_DAYS; i += 1) {
    // Built by date arithmetic rather than by adding 86 400 000 ms, so a column still lands
    // on midnight across a daylight-saving change. Two days a year, silently wrong otherwise.
    const day = new Date(start)
    day.setDate(start.getDate() + i)
    days.push(day)
  }

  const end = new Date(start)
  end.setDate(start.getDate() + TIMELINE_DAYS)

  return { start: start.getTime(), end: end.getTime(), days }
}

/** Where an instant falls across the window, as a percentage. Not clamped. */
export function positionOf(instant: number, window: TimelineWindow): number {
  return ((instant - window.start) / (window.end - window.start)) * 100
}

/**
 * A restriction as a bar, or `null` when it falls entirely outside the window.
 *
 * `null` is a real answer and the caller must say so rather than drop it: a freeze
 * scheduled for next month is the single most likely thing a reader would assume this view
 * was showing them.
 */
export function barFor(
  restriction: RestrictionSummary,
  window: TimelineWindow,
): TimelineBar | null {
  const starts = Date.parse(restriction.startsAt)
  const ends = Date.parse(restriction.endsAt)
  if (Number.isNaN(starts) || Number.isNaN(ends)) return null
  if (ends <= window.start || starts >= window.end) return null

  const clippedStart = Math.max(starts, window.start)
  const clippedEnd = Math.min(ends, window.end)

  const left = positionOf(clippedStart, window)
  let width = positionOf(clippedEnd, window) - left

  // Floor the width, then pull the bar back inside rather than letting it overhang — an
  // overhang would read as "runs past the fortnight", which is a different claim.
  width = Math.max(width, MIN_BAR_PERCENT)

  return {
    restriction,
    left: Math.min(left, 100 - width),
    width,
    fromBefore: starts < window.start,
    runsOn: ends > window.end,
    duration: formatDuration(ends - starts),
  }
}

/**
 * The stretches inside the window when no hard freeze is in force — the question this view
 * exists to answer, and the one neither "active now" nor "then what" can.
 *
 * Advisories are deliberately not counted. An advisory does not refuse a deployment
 * (`01-domain.md`), so runway that an advisory overlaps is still runway; drawing it as
 * blocked would make the one figure a release is planned against pessimistic, and a
 * pessimistic figure gets ignored.
 *
 * Anything already past is not runway either, so the first gap starts at `now`.
 */
export function clearRunways(
  restrictions: RestrictionSummary[],
  window: TimelineWindow,
  now: Date = new Date(),
): TimelineRunway[] {
  const from = Math.max(now.getTime(), window.start)

  const blocked = restrictions
    .filter((r) => r.level === 'HARD_FREEZE')
    .map((r) => ({ start: Date.parse(r.startsAt), end: Date.parse(r.endsAt) }))
    .filter((r) => !Number.isNaN(r.start) && !Number.isNaN(r.end))
    .map((r) => ({ start: Math.max(r.start, from), end: Math.min(r.end, window.end) }))
    .filter((r) => r.end > r.start)
    .sort((a, b) => a.start - b.start)

  // Merge, so two overlapping freezes leave one blocked stretch rather than a false gap
  // between the start of the second and the end of the first.
  const merged: { start: number; end: number }[] = []
  for (const span of blocked) {
    const last = merged[merged.length - 1]
    if (last && span.start <= last.end) last.end = Math.max(last.end, span.end)
    else merged.push({ ...span })
  }

  const gaps: { start: number; end: number }[] = []
  let cursor = from
  for (const span of merged) {
    if (span.start > cursor) gaps.push({ start: cursor, end: span.start })
    cursor = Math.max(cursor, span.end)
  }
  if (cursor < window.end) gaps.push({ start: cursor, end: window.end })

  return gaps
    .filter((gap) => gap.end - gap.start >= MIN_RUNWAY_MS)
    .map((gap) => {
      const left = positionOf(gap.start, window)
      return {
        left,
        width: Math.min(positionOf(gap.end, window) - left, 100 - left),
        start: gap.start,
        duration: formatDuration(gap.end - gap.start),
      }
    })
}

/**
 * A duration a person would say out loud: `45m`, `8h`, `2 days`.
 *
 * Whole units only. "1.7 days" is not how anybody describes a freeze, and the extra
 * precision is spurious against windows that are set to the hour.
 */
export function formatDuration(ms: number): string {
  if (ms < MS_PER_HOUR) {
    const minutes = Math.max(1, Math.round(ms / 60_000))
    return `${minutes}m`
  }
  if (ms < MS_PER_DAY) return `${Math.round(ms / MS_PER_HOUR)}h`

  const days = Math.floor(ms / MS_PER_DAY)
  const hours = Math.round((ms - days * MS_PER_DAY) / MS_PER_HOUR)
  if (days < 3 && hours > 0) return `${days}d ${hours}h`
  return `${days} ${days === 1 ? 'day' : 'days'}`
}
