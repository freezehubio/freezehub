import { Link } from 'react-router'
import type { RestrictionSummary } from '../../types/api'
import { formatShort, localZoneName } from '../../utils/datetime'
import {
  TIMELINE_DAYS,
  barFor,
  clearRunways,
  positionOf,
  timelineWindow,
  type TimelineBar,
  type TimelineWindow,
} from './scheduleGeometry'
import styles from './ScheduleTimeline.module.css'

/**
 * The schedule itself, as a fortnight (`FZ-191`, direction `1b`).
 *
 * The dashboard already answers *what is on now* and *what happens next*. Neither answers
 * the question a release is actually planned against — **when is there room?** — because a
 * list cannot show two windows overlapping, and it cannot show the gap between them at all.
 * Time as an axis shows both without saying anything.
 *
 * **Colour follows the level here, not the status**, which differs from
 * `RestrictionDetailPage` and is deliberate. There, magenta means "this is stopping
 * deployments *now*", and a scheduled freeze drawn in magenta would overstate it. Here the
 * horizontal axis *is* time: where a bar sits relative to the now-rule already says whether
 * it is in force, so spending colour on that would say it twice and leave nothing to
 * distinguish a hard freeze from an advisory — which is the distinction that decides
 * whether a deployment is refused.
 */
export function ScheduleTimeline({
  restrictions,
  now = new Date(),
}: {
  restrictions: RestrictionSummary[]
  now?: Date
}) {
  const window = timelineWindow(now)

  const drawn = restrictions
    .map((restriction) => barFor(restriction, window))
    .filter((bar): bar is TimelineBar => bar !== null)
    .sort((a, b) => a.left - b.left || a.width - b.width)

  // Named rather than silently dropped: a freeze scheduled for next month is the most
  // likely thing a reader would assume this view was showing them.
  const beyond = restrictions.length - drawn.length
  const runways = clearRunways(restrictions, window, now)
  const nowAt = positionOf(now.getTime(), window)

  return (
    <section className={styles.schedule} aria-labelledby="schedule-heading">
      <h2 className={styles.heading} id="schedule-heading">
        Next {TIMELINE_DAYS} days
      </h2>
      <p className={styles.note}>
        One row per restriction, all times {localZoneName(now)}. The rule is now.
      </p>

      <div className={styles.scroller}>
        <div className={styles.chart}>
          {/*
            * Not `aria-hidden`. The names are links — the only way from this view to a
            * restriction — and a focusable element inside an `aria-hidden` subtree is both
            * unreachable to a screen reader and still reachable by Tab, which is the worst
            * of the two. Each row's sentence sits here beside its name rather than in the
            * plot, so the two are read together instead of as two separate lists.
            */}
          <ol className={styles.gutter}>
            <li className={styles.axisSpacer} aria-hidden="true" />
            {drawn.map((bar) => (
              <li className={styles.rowLabel} key={bar.restriction.id}>
                <Link className={styles.rowName} to={`/restrictions/${bar.restriction.id}`}>
                  {bar.restriction.name}
                </Link>
                <span className={styles.srOnly}>{sentenceFor(bar)}</span>
              </li>
            ))}
            <li className={styles.rowLabel}>
              <span className={styles.runwayLabel}>Clear runway</span>
            </li>
          </ol>

          {/*
            * Decorative in full: everything it draws is stated in the gutter beside it or
            * in the summary below. Hiding it wholesale is what stops a screen reader
            * reading a stack of empty boxes.
            */}
          <div className={styles.plot} aria-hidden="true">
            <Axis window={window} />

            {/*
              * One rule for the whole plot rather than one per row, so it reads as a single
              * line through the fortnight instead of a stack of ticks.
              */}
            <div className={styles.nowRule} style={{ left: `${nowAt}%` }} />

            <div className={styles.rows}>
              {drawn.map((bar) => (
                <Row bar={bar} key={bar.restriction.id} />
              ))}

              <div className={styles.row}>
                <div className={styles.runwayTrack}>
                  {runways.length === 0 ? (
                    <span className={styles.noRunway}>
                      No clear day in the next {TIMELINE_DAYS}.
                    </span>
                  ) : (
                    runways.map((runway) => (
                      <span
                        className={styles.runway}
                        key={runway.start}
                        style={{ left: `${runway.left}%`, width: `${runway.width}%` }}
                      >
                        <span className={styles.runwayText}>{runway.duration}</span>
                      </span>
                    ))
                  )}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/*
        * The sentence a reader would otherwise have to assemble from the bars, and the one
        * a screen reader gets instead of a chart it cannot see.
        */}
      <p className={styles.summary}>
        {runwaySentence(runways.length === 0 ? null : runways[0])}
        {beyond > 0 && (
          <>
            {' '}
            {beyond} {beyond === 1 ? 'restriction starts' : 'restrictions start'} after this
            fortnight and {beyond === 1 ? 'is' : 'are'} not drawn —{' '}
            <Link to="/restrictions">see all restrictions</Link>.
          </>
        )}
      </p>
    </section>
  )
}

function runwaySentence(first: { start: number; duration: string } | null): string {
  if (first === null) return `Every day in the next ${TIMELINE_DAYS} is covered by a hard freeze.`
  const startsNow = first.start <= Date.now() + 60_000
  return startsNow
    ? `Clear for ${first.duration} from now.`
    : `Next clear runway: ${first.duration} from ${formatShort(new Date(first.start).toISOString())}.`
}

/** The day scale. Numbers only, with the month named where it turns over. */
function Axis({ window }: { window: TimelineWindow }) {
  return (
    <div className={styles.axis} aria-hidden="true">
      {window.days.map((day, index) => (
        <span
          className={index === 0 ? `${styles.axisDay} ${styles.axisToday}` : styles.axisDay}
          key={day.getTime()}
        >
          {day.getDate() === 1 || index === 0
            ? new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' }).format(day)
            : day.getDate()}
        </span>
      ))}
    </div>
  )
}

/** What the bar would say if it could be read. Lives beside the name, in the gutter. */
function sentenceFor(bar: TimelineBar): string {
  const { restriction } = bar
  return (
    `${restriction.name}: ${restriction.level === 'HARD_FREEZE' ? 'hard freeze' : 'advisory'}, ` +
    `${formatShort(restriction.startsAt)} to ${formatShort(restriction.endsAt)}, ${bar.duration}` +
    `${bar.fromBefore ? ', began before this fortnight' : ''}` +
    `${bar.runsOn ? ', continues past this fortnight' : ''}.`
  )
}

function Row({ bar }: { bar: TimelineBar }) {
  const hard = bar.restriction.level === 'HARD_FREEZE'

  return (
    <div className={styles.row}>
      <div className={styles.track}>
        <span
          className={`${styles.bar} ${hard ? styles.barHard : styles.barAdvisory}`}
          style={{ left: `${bar.left}%`, width: `${bar.width}%` }}
        >
          {/*
            * The duration rather than the name: the name is already in the gutter beside
            * it, and a bar two days wide has room for one of the two.
            *
            * The arrows are load-bearing, not decoration. A freeze that began before this
            * fortnight is drawn from the left edge, which without a mark reads as *starts
            * today* — a different and wrong claim. The mockup's own convention, and it
            * survives the bar being magenta, which a border did not.
            */}
          <span className={styles.barText}>
            {bar.fromBefore && '‹ '}
            {bar.duration}
            {bar.runsOn && ' ›'}
          </span>
        </span>
      </div>
    </div>
  )
}
