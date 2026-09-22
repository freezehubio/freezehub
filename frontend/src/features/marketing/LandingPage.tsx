import { Link } from 'react-router'
import { useAuth } from '../auth/authContext'
import styles from './LandingPage.module.css'

/**
 * The public landing page — `1j` (FZ-111).
 *
 * <b>Inside the application, not a separate site.</b> `FZ-111` left that open; it is
 * resolved here as a public route because CloudFront already serves this bundle at
 * `domain_name` and `Deploy frontend` already publishes it. A separate estate would mean a
 * second bucket, distribution and certificate, and renaming the hostname Cognito callbacks
 * already point at.
 *
 * <b>One thing `1j` draws is still not built here.</b> The deck's code sample is
 * `uses: freezehub/freeze-check@v1`, which is not a real reference; the sample below is
 * the shipped container from `connectors/README.md`. `gtm/README.md` forbids a claim that
 * is not true today, and a landing page's code sample is the first thing a visitor copies.
 *
 * <b>The trial CTA had a precise trigger, and `FZ-185` met it.</b> `FZ-082` built
 * `POST /api/signup` and no signup screen, and an endpoint existing did not make "start
 * free trial" true while there was nothing for a visitor to fill in. `/signup` now exists,
 * so the button does too — as the <i>secondary</i> action. "Book a demo" stays primary:
 * at this stage the conversations are worth more than the volume.
 */
export function LandingPage() {
  const { isAuthenticated } = useAuth()

  return (
    <div className={styles.page}>
      <header className={styles.nav}>
        <span className={styles.brand}>FreezeHub</span>
        <nav className={styles.navLinks}>
          <a href="#product">Product</a>
          <a href="#pipeline">Integrations</a>
          <a href="#pricing">Pricing</a>
        </nav>
        <Link className="btn btn-secondary" to={isAuthenticated ? '/dashboard' : '/signin'}>
          {isAuthenticated ? 'Open dashboard' : 'Sign in'}
        </Link>
      </header>

      <section className={styles.hero}>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>Deployment freeze management</p>
          <h1 className={styles.headline}>Is the freeze on, and does it apply to me?</h1>
          <p className={styles.sub}>
            Freeze information lives in three Slack threads, a calendar invite and somebody's
            memory. FreezeHub is one authoritative place to declare a freeze, tell everyone,
            and let pipelines ask before they deploy.
          </p>
          {/*
            Both doors, demo still primary (`FZ-185`). The trigger this file named — change
            the trial CTA when a signup *page* ships, not when the API does — has now been
            met by that page existing.

            "See what a pipeline asks" gave way rather than being added to: three
            equal-weight actions in one hero is no hierarchy at all, and #pipeline is still
            one click away as "Integrations" in the nav above.
          */}
          <div className={styles.ctas}>
            <a className="btn btn-primary" href="#demo">Book a demo</a>
            <Link className="btn btn-secondary" to="/signup">Start free trial</Link>
          </div>
        </div>

        <dl className={styles.spec}>
          <div>
            <dt>2</dt>
            <dd>levels — advisory, or a hard freeze that blocks</dd>
          </div>
          <div>
            <dt>3</dt>
            <dd>scope dimensions — teams, applications, environments</dd>
          </div>
          <div>
            <dt>1</dt>
            <dd>REST call from any pipeline. No plugin to install</dd>
          </div>
        </dl>
      </section>

      <hr className="hr" />

      <section id="product" className={styles.features}>
        <article>
          <p className="card-kicker">Declare</p>
          <h2>One record, not three threads</h2>
          <p>
            Name it, say why, set the window and the scope. Status moves itself: scheduled,
            active, completed.
          </p>
        </article>
        <article>
          <p className="card-kicker">Tell everyone</p>
          <h2>Slack, email, webhook</h2>
          <p>
            Scheduled, starting soon, activated, cancelled and completed are sent on the
            lifecycle, with delivery you can inspect and retry.
          </p>
        </article>
        <article>
          <p className="card-kicker">Let machines ask</p>
          <h2>Allow or block, with a reason</h2>
          <p>
            Any CI system can call the policy API before deploying. Every answer is kept, so
            the audit is already written.
          </p>
        </article>
      </section>

      <section id="pipeline" className={styles.pipeline}>
        <div className={styles.pipelineCopy}>
          <p className="card-kicker">In your pipeline</p>
          <h2>One step, immediately before the deploy</h2>
          <p>
            The same container runs on GitHub Actions, GitLab CI and Jenkins, so two teams on
            two CI systems get the same answer from the same freeze.
          </p>
          <p className={styles.note}>
            It fails closed by default: if FreezeHub cannot be reached, your pipeline does not
            get an <code>ALLOW</code>.
          </p>
        </div>
        <pre className={styles.code}>
          <code>{`- name: FreezeHub check
  env:
    FREEZEHUB_URL: \${{ vars.FREEZEHUB_URL }}
    FREEZEHUB_API_KEY: \${{ secrets.FREEZEHUB_API_KEY }}
  run: |
    docker run --rm \\
      -e FREEZEHUB_URL -e FREEZEHUB_API_KEY \\
      -e FREEZEHUB_APPLICATION=checkout-api \\
      -e FREEZEHUB_ENVIRONMENT=production \\
      ghcr.io/freezehubio/freeze-check:v1

# → BLOCK · Black Friday Freeze`}</code>
        </pre>
      </section>

      <section id="pricing" className={styles.pricing}>
        <h2>Priced per registered application</h2>
        <p className={styles.pricingSub}>
          Users, teams, environments, freezes and policy evaluations are unlimited on every
          plan. Charging per seat would charge you for telling everybody, and a freeze half
          your organization has not heard about is not a freeze.
        </p>
        <div className={styles.tableWrap}>
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Plan</th>
                <th scope="col">Freezes block</th>
                <th scope="col">Applications</th>
                <th scope="col">Per month</th>
                <th scope="col">Check history</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">Free</th>
                <td><span className="tag tag-outline">advisory only</span></td>
                <td>5</td><td>$0</td><td>7 days</td>
              </tr>
              <tr>
                <th scope="row">Starter</th><td>yes</td><td>10</td><td>$99</td><td>90 days</td>
              </tr>
              <tr>
                <th scope="row">Growth</th><td>yes</td><td>50</td><td>$349</td><td>1 year</td>
              </tr>
              <tr>
                <th scope="row">Scale</th><td>yes</td><td>200</td><td>$899</td><td>1 year</td>
              </tr>
              <tr>
                <th scope="row">Enterprise</th>
                <td>yes</td><td>unlimited</td><td>from $2,000</td><td>up to 10 years</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className={styles.note}>
          The audit trail is kept indefinitely on every plan, including Free.
        </p>
      </section>

      <section id="demo" className={styles.closing}>
        <h2>FreezeHub is pre-launch</h2>
        <p>
          We are looking for design partners, which means a demo, a conversation about
          whether it fits, and an organization we set up for you. You would be among the
          first customers, with the access to us that implies.
        </p>
        <p>
          If you would rather just try it, <Link to="/signup">start a free trial</Link> —
          fourteen days, every feature, no card. We would still like the conversation.
        </p>
        <p className={styles.note}>
          What it does not do: FreezeHub cannot stop your deployment and does not try to.
          Enforcement stays in your pipeline, which asks and then decides.
        </p>
      </section>

      <footer className={styles.footer}>
        <span>FreezeHub</span>
        <Link to="/signin">Sign in</Link>
      </footer>
    </div>
  )
}
