import { apiRequest } from './client'

/** What the endpoint always answers, whatever happened (`FZ-082`). */
export interface SignupAcknowledgement {
  message: string
}

/**
 * Start a free trial (`FZ-185`, endpoint `FZ-082`).
 *
 * <b>No token</b> — this is one of two endpoints reachable without a credential, because
 * the person calling it has no account yet. That is the point of it.
 *
 * <b>It resolves the same way whether or not anything was created.</b> An address already
 * in use gets the same `202` and the same body as a new one, so there is nothing here for
 * a caller to branch on, deliberately: a difference would let anyone learn which companies
 * use FreezeHub by trying their domains. Do not add a "already registered" path to this —
 * the information to build one does not exist on this side, and asking the backend for it
 * would be asking it to become a customer-enumeration oracle.
 */
export function signUp(company: string, email: string): Promise<SignupAcknowledgement> {
  return apiRequest<SignupAcknowledgement>('/api/signup', {
    method: 'POST',
    body: { company, email },
  })
}
