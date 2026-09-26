import { apiRequest } from './client'

/** What the endpoint always answers. It says nothing about what was stored (`FZ-083`). */
export interface DemoRequestAcknowledgement {
  message: string
}

export interface DemoRequestFields {
  name: string
  email: string
  company: string
  teamSize?: string
  message?: string
}

/**
 * Ask for a demo (`FZ-213`, endpoint `FZ-083`).
 *
 * <b>No token</b> — one of the three endpoints reachable without a credential, because the
 * person calling it has no account and the whole point is that they might never get one.
 *
 * <b>The row is the lead, not the message.</b> The backend stores the request before it
 * tries to tell anyone, so a Slack outage loses the notification and not the prospect. The
 * `202` means *recorded*, which is why this resolves rather than waiting for a human.
 *
 * `source` is sent so a lead can be attributed later. It is set here rather than passed in,
 * because the only caller is the landing page and a value the form could set would be a
 * value a visitor could set.
 */
export function requestDemo(fields: DemoRequestFields): Promise<DemoRequestAcknowledgement> {
  return apiRequest<DemoRequestAcknowledgement>('/api/demo-requests', {
    method: 'POST',
    body: { ...fields, source: 'landing' },
  })
}
