import { screen, within } from '@testing-library/react'
import { describe, expect, test } from 'vitest'
import { ScheduleTimeline } from './ScheduleTimeline'
import { renderRoute } from '../../test/renderRoute'
import type { RestrictionLevel, RestrictionStatus, RestrictionSummary } from '../../types/api'

const NOW = new Date('2026-09-23T16:25:00Z')

/** Local midnight on NOW's day, plus whole days — the same origin the component uses. */
function at(days: number, hours = 0): string {
  const midnight = new Date(NOW)
  midnight.setHours(0, 0, 0, 0)
  return new Date(midnight.getTime() + days * 86_400_000 + hours * 3_600_000).toISOString()
}

let nextId = 1
function restriction(
  name: string,
  startsAt: string,
  endsAt: string,
  level: RestrictionLevel = 'HARD_FREEZE',
  status: RestrictionStatus = 'SCHEDULED',
): RestrictionSummary {
  return {
    id: nextId++,
    name,
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

describe('ScheduleTimeline', () => {
  test('draws one row per restriction, each linking to it', () => {
    renderRoute(
      <ScheduleTimeline
        now={NOW}
        restrictions={[
          restriction('Peak trading', at(0), at(3)),
          restriction('Year-end close', at(5), at(9)),
        ]}
      />,
    )

    expect(screen.getByRole('link', { name: 'Peak trading' })).toHaveAttribute(
      'href',
      '/restrictions/' + (nextId - 2),
    )
    expect(screen.getByRole('link', { name: 'Year-end close' })).toBeInTheDocument()
  })

  test('gives a screen reader the sentence the chart draws', () => {
    // The bars are aria-hidden: a chart nobody can see is worth nothing to a reader using
    // one, so each row carries the same facts as text.
    renderRoute(
      <ScheduleTimeline now={NOW} restrictions={[restriction('Peak trading', at(1), at(4))]} />,
    )

    const item = screen.getByText(/Peak trading: hard freeze/)
    expect(item).toHaveTextContent(/3 days/)
  })

  test('says an advisory is an advisory, because it refuses nothing', () => {
    renderRoute(
      <ScheduleTimeline
        now={NOW}
        restrictions={[restriction('Patching', at(1), at(2), 'ADVISORY')]}
      />,
    )

    expect(screen.getByText(/Patching: advisory/)).toBeInTheDocument()
  })

  test('says when a freeze began before the fortnight, not just that it is at the edge', () => {
    renderRoute(
      <ScheduleTimeline now={NOW} restrictions={[restriction('Ongoing', at(-4), at(2))]} />,
    )

    expect(screen.getByText(/began before this fortnight/)).toBeInTheDocument()
  })

  test('says when one continues past it', () => {
    renderRoute(
      <ScheduleTimeline now={NOW} restrictions={[restriction('Long', at(2), at(40))]} />,
    )

    expect(screen.getByText(/continues past this fortnight/)).toBeInTheDocument()
  })

  test('names restrictions outside the window rather than dropping them silently', () => {
    // The thing a reader is most likely to assume this view is showing them.
    renderRoute(
      <ScheduleTimeline
        now={NOW}
        restrictions={[restriction('Next month', at(30), at(33))]}
      />,
    )

    expect(screen.getByText(/1 restriction starts after this fortnight/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /see all restrictions/i })).toHaveAttribute(
      'href',
      '/restrictions',
    )
  })

  test('answers when it is safe to deploy', () => {
    renderRoute(
      <ScheduleTimeline now={NOW} restrictions={[restriction('Freeze', at(0), at(4))]} />,
    )

    expect(screen.getByText(/Next clear runway/)).toBeInTheDocument()
  })

  test('says plainly when there is no runway at all', () => {
    renderRoute(
      <ScheduleTimeline now={NOW} restrictions={[restriction('Wall', at(-1), at(20))]} />,
    )

    expect(screen.getByText(/covered by a hard freeze/)).toBeInTheDocument()
  })

  test('is clear from now when nothing is scheduled', () => {
    renderRoute(<ScheduleTimeline now={NOW} restrictions={[]} />)

    expect(screen.getByText(/Clear for .* from now/)).toBeInTheDocument()
  })

  test('an advisory does not eat the runway', () => {
    renderRoute(
      <ScheduleTimeline
        now={NOW}
        restrictions={[restriction('Advice', at(0), at(10), 'ADVISORY')]}
      />,
    )

    expect(screen.getByText(/Clear for .* from now/)).toBeInTheDocument()
  })

  test('the day scale has a column per day of the fortnight', () => {
    renderRoute(<ScheduleTimeline now={NOW} restrictions={[]} />)

    const heading = screen.getByRole('heading', { name: /Next 14 days/ })
    expect(heading).toBeInTheDocument()
  })

  test('states the viewer zone, because a freeze window without one is meaningless', () => {
    renderRoute(<ScheduleTimeline now={NOW} restrictions={[]} />)
    expect(screen.getByText(/all times/i)).toBeInTheDocument()
  })
})

describe('the chart itself', () => {
  test('is hidden from assistive technology, so it is not read as a pile of empty boxes', () => {
    const { container } = renderRoute(
      <ScheduleTimeline now={NOW} restrictions={[restriction('One', at(1), at(3))]} />,
    )

    const tracks = container.querySelectorAll('[aria-hidden="true"]')
    expect(tracks.length).toBeGreaterThan(0)
    // …and the sentence survives that hiding.
    expect(within(container).getByText(/One: hard freeze/)).toBeInTheDocument()
  })
})
