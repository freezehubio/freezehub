/**
 * The single place that talks to the API.
 *
 * Everything else goes through here so that the auth header, the base URL and error
 * normalisation exist in exactly one place — no component calls `fetch` directly
 * (05-frontend.md, API interaction conventions).
 */

// The fallback is for local development only — it matches the backend's local port
// (server.port in application.yml). Any deployed build sets VITE_API_BASE_URL, because
// the API lives on a different host there, not just a different port.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8099'

/** One invalid field, as named by the backend (RFC 9457 `errors` extension). */
export interface ApiFieldError {
  field: string
  message: string
}

/** Raised for any non-2xx response, carrying the status the UI branches on. */
export class ApiError extends Error {
  readonly status: number
  /** Empty unless the backend rejected specific fields. */
  readonly fieldErrors: ApiFieldError[]

  /**
   * The plan limit that refused this, when the backend sent one (`FZ-081`).
   *
   * Carried so a 402 can render as "10 of 10 applications used" with the upgrade path,
   * rather than as a generic error. Absent on every other status.
   */
  readonly planLimit: PlanLimitRefusal | null

  constructor(
    status: number,
    message: string,
    fieldErrors: ApiFieldError[] = [],
    planLimit: PlanLimitRefusal | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.planLimit = planLimit
  }

  /** Token missing, invalid, or resolving to no user — the caller must sign in again. */
  get isUnauthorized(): boolean {
    return this.status === 401
  }

  /** Unknown *or* another tenant's resource; the backend never distinguishes them. */
  get isNotFound(): boolean {
    return this.status === 404
  }

  /** State conflict — the resource has moved on and should be refetched. */
  get isConflict(): boolean {
    return this.status === 409
  }

  /** Refused by the plan, not by validation or permission (`D-22`). */
  get isPlanLimit(): boolean {
    return this.status === 402
  }

  /**
   * No answer at all, rather than an answer that says no (`FZ-124`).
   *
   * Status `0` because there was no response to take one from — the request was abandoned
   * before the server said anything. It is what the retry policy branches on: a timeout
   * has already cost its whole deadline before it is reported, and is not retried at all —
   * see `QueryProvider` for what that was decided against.
   */
  get isTimeout(): boolean {
    return this.status === 0
  }
}

interface ProblemDetail {
  detail?: string
  title?: string
  errors?: ApiFieldError[]
  /** Pre-FZ-061 shape. Kept only so an older backend does not produce a blank message. */
  message?: string
  /** 402 extensions (`FZ-081`). Present only on a plan refusal. */
  feature?: unknown
  plan?: unknown
  resource?: unknown
  limit?: unknown
  current?: unknown
}

/** What refused, and by how much (`FZ-081`). */
export interface PlanLimitRefusal {
  plan: string
  resource: string
  limit: number
  current: number
}

/**
 * Turns a failed response into an ApiError.
 *
 * The backend answers with RFC 9457 Problem Details (FZ-061), so `detail` is expected.
 * The defensive parsing is still here on purpose: not every failure reaches a controller.
 * A 401 from the security chain has no body at all, and anything served by a proxy or a
 * load balancer in front of the API is out of the backend's hands entirely — so this must
 * never depend on the body being what it should be.
 */
/**
 * The numbers a 402 carries as Problem Details extensions.
 *
 * Read defensively: a 402 from anywhere but our own handler will not have them, and a
 * usage figure rendered from a missing field would be a confident lie.
 */
function planLimitFrom(body: ProblemDetail): PlanLimitRefusal | null {
  if (typeof body.plan !== 'string' || typeof body.limit !== 'number') return null
  return {
    plan: body.plan,
    resource: typeof body.resource === 'string' ? body.resource : 'resources',
    limit: body.limit,
    current: typeof body.current === 'number' ? body.current : body.limit,
  }
}

/**
 * A plan refusal, in words someone can act on.
 *
 * Composed here rather than at each call site, so every screen that already renders
 * `error.message` gets the useful version without being changed — and no future screen
 * can forget to. The backend's own `detail` is accurate but written for an API client;
 * this is written for the person who just clicked a button.
 */
function describePlanLimit(limit: PlanLimitRefusal): string {
  return (
    `Your ${limit.plan} plan allows ${limit.limit} ${limit.resource}, ` +
    `and you are using ${limit.current}. Upgrade under Settings → Billing to add more.`
  )
}

/** What the plan does not carry at all (`FZ-143`). Distinct from a limit: there is no count. */
export interface PlanFeatureRefusal {
  plan: string
  feature: string
}

/**
 * A capability refusal, told apart from a limit by the absence of numbers.
 *
 * `PlanFeatureUnavailableException` sends `plan` and `feature` and deliberately no `limit`,
 * so `planLimitFrom` declines it — which is correct, and is why this exists rather than a
 * looser parse there. Nothing may render a usage bar for something that has no usage.
 */
function planFeatureFrom(body: ProblemDetail): PlanFeatureRefusal | null {
  if (typeof body.plan !== 'string' || typeof body.feature !== 'string') return null
  if (typeof body.limit === 'number') return null
  return { plan: body.plan, feature: body.feature }
}

/**
 * The same shape of sentence a limit gets, for the same reason (`FZ-146`).
 *
 * The backend's own `detail` is accurate and ends there. This is the one 402 a free
 * organization actually meets, so leaving it without a way out made the commonest refusal
 * in the product the only one that did not say what to do about it.
 */
function describePlanFeature(refusal: PlanFeatureRefusal): string {
  return (
    `Your ${refusal.plan} plan does not include ${refusal.feature}. ` +
    `Upgrade under Settings → Billing.`
  )
}

async function toApiError(response: Response): Promise<ApiError> {
  const fallback = `Request failed (${response.status})`
  try {
    const text = await response.text()
    if (!text) return new ApiError(response.status, fallback)

    try {
      const body = JSON.parse(text) as ProblemDetail
      const fieldErrors = Array.isArray(body.errors) ? body.errors : []
      const stated = body.detail ?? body.message ?? body.title

      // Field errors are spelled out rather than left as "the request has 3 invalid
      // fields", which tells someone staring at a form nothing about which three.
      const message = fieldErrors.length
        ? fieldErrors.map((error) => `${error.field} ${error.message}`).join(', ')
        : stated

      const planLimit = planLimitFrom(body)
      const planFeature = planLimit ? null : planFeatureFrom(body)

      return new ApiError(
        response.status,
        planLimit
          ? describePlanLimit(planLimit)
          : planFeature
            ? describePlanFeature(planFeature)
            : message && message.trim()
              ? message
              : fallback,
        fieldErrors,
        planLimit,
      )
    } catch {
      // Not JSON — use the raw text if it is short enough to be a useful message.
      return new ApiError(response.status, text.length <= 200 ? text : fallback)
    }
  } catch {
    return new ApiError(response.status, fallback)
  }
}

/**
 * How long any one request may take before it is abandoned (`FZ-124`).
 *
 * Generous, because this is a backstop and not a performance budget: it exists so a
 * connection that is accepted and then never answered ends in an error somebody can act on
 * rather than a spinner that never resolves. The realistic cause is a load balancer holding
 * the socket to a task that has wedged — an unreachable API already fails in milliseconds.
 */
const REQUEST_TIMEOUT_MS = 20_000

export interface RequestOptions {
  method?: string
  body?: unknown
  token?: string | null
  signal?: AbortSignal
  /** Overridable per call, for the rare request that has earned more time. */
  timeoutMs?: number
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, token, signal, timeoutMs = REQUEST_TIMEOUT_MS } = options

  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`

  /*
   * Built by hand rather than with `AbortSignal.any` + `AbortSignal.timeout`, which say
   * this in two lines. Two reasons: the composed signal cannot tell us *which* input
   * fired, and that distinction is the whole story — TanStack Query aborts on unmount and
   * on refetch, and rendering those as failures would flash an error every time somebody
   * navigates away. Doing it manually also keeps the wrapper on APIs every browser and the
   * test environment have had for years.
   */
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  const abortWithCaller = () => controller.abort()
  signal?.addEventListener('abort', abortWithCaller)
  if (signal?.aborted) abortWithCaller()

  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
  } catch (failure) {
    if (timedOut) {
      // Deliberately an ApiError: every screen already renders `error.message`, so this
      // reaches the person without a single page being changed.
      throw new ApiError(
        0,
        `The server did not answer within ${Math.round(timeoutMs / 1000)} seconds. ` +
          'It may be overloaded or restarting — try again in a moment.',
      )
    }
    // The caller's own abort, or a genuine network failure. Rethrown as it came: a
    // cancellation must stay a cancellation, or TanStack Query reports navigating away
    // as an error.
    throw failure
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abortWithCaller)
  }

  if (!response.ok) {
    throw await toApiError(response)
  }

  // 204 and other empty responses have nothing to parse.
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}
