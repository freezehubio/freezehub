# FreezeHub connectors

Ask FreezeHub whether a deployment may proceed, and fail the build if it may not.

| | |
|---|---|
| [`freeze-check.sh`](./freeze-check.sh) | **the one implementation** — POSIX shell, `curl` + `jq` |
| [`Dockerfile`](./Dockerfile) | `ghcr.io/freezehubio/freeze-check` — the script, packaged. **The connector.** |
| [`action.yml`](./action.yml) | GitHub Action — a tested reference implementation, see below |
| [`templates/`](./templates) | GitLab component — likewise |
| [`test/`](./test) | what proves all of the above still fail closed |
| [`LICENSE`](./LICENSE) | Apache-2.0 — free to use, subscription or not (`D-26`) |

## One source, one artifact

There is one implementation and one published thing: **the image**. Every CI system below reaches FreezeHub by running it.

That is not a compromise — it is what the market actually looks like. Nearly every CI system runs containers, so one artifact covers nearly all of them, and a customer running GitLab in one team and Jenkins in another gets **the same answer from the same freeze** because it is the same binary (`D-24`). Per-ecosystem plugins would drift in exactly the places that matter — what a timeout means, whether a `401` fails open, how an unregistered name is reported — and that drift shows up as one team deploying during a freeze that stopped another.

> **The image is published.** `ghcr.io/freezehubio/freeze-check:v1` is public and needs no credentials to pull — `linux/amd64` and `linux/arm64`, so it runs on a hosted runner and on Apple hardware alike. `v1` moves with each release; pin an exact version such as `v1.0.0` if you would rather decide when behaviour changes.

## What it does, and what it does not

One HTTP request to your FreezeHub organization, turned into an exit code.

| Exit | Meaning |
|---|---|
| `0` | allowed — deploy. Advisory restrictions, if any, are printed |
| `1` | blocked — a restriction is in force, or a name is not registered |
| `2` | not evaluated — misconfiguration, or FreezeHub could not be asked |

`1` and `2` are separate on purpose. "You may not deploy" and "I could not find out" are different facts, and only the second is your infrastructure's problem.

**It cannot stop a deployment.** Enforcement is your pipeline failing the step. Skip the step and you skip the gate. That is a deliberate limit, and it is why FreezeHub needs no credentials for your repositories, no webhook from them, and no agent inside your infrastructure — the only thing that leaves your network is a question.

## Configuration

The same everywhere, because it is the same program.

| | | |
|---|---|---|
| `FREEZEHUB_URL` | — | required |
| `FREEZEHUB_API_KEY` | — | required; a credential |
| `FREEZEHUB_APPLICATION` | — | required; exactly as registered, **including case** |
| `FREEZEHUB_ENVIRONMENT` | — | required; likewise |
| `FREEZEHUB_ON_ERROR` | `block` | what silence means — the one genuine choice |
| `FREEZEHUB_TIMEOUT` | `10` | seconds |

`FREEZEHUB_ON_ERROR=allow` covers a FreezeHub outage. It does **not** cover a missing variable or a rejected credential — both exit `2` regardless, because otherwise revoking a key or fat-fingering a variable would silently switch enforcement off for every pipeline still using it.

An API key reaches `/api/policy/**` and nothing else: it cannot read your restrictions, change your catalog, or issue another key. A leaked CI variable is not an account takeover.

**A rate-limited answer is waited out, not failed.** FreezeHub limits the policy endpoint, and the check honours a `Retry-After` and asks once more — up to 30 seconds, after which it becomes an ordinary "could not be asked" and `FREEZEHUB_ON_ERROR` decides. `FREEZEHUB_TIMEOUT` bounds each attempt, not the wait between them. Nothing needs configuring for this; it is noted because a build that pauses for a few seconds here is working, not stuck.

---

# Integration guidelines

## GitHub Actions

```yaml
- name: FreezeHub check
  env:
    FREEZEHUB_URL: https://freezehub.example.com
    FREEZEHUB_API_KEY: ${{ secrets.FREEZEHUB_API_KEY }}
  run: |
    docker run --rm \
      -e FREEZEHUB_URL -e FREEZEHUB_API_KEY \
      -e FREEZEHUB_APPLICATION=payments-api \
      -e FREEZEHUB_ENVIRONMENT=production \
      -e GITHUB_ACTOR -e GITHUB_SHA -e GITHUB_SERVER_URL -e GITHUB_REPOSITORY -e GITHUB_RUN_ID \
      ghcr.io/freezehubio/freeze-check:v1
```

Put it in the job that deploys, **immediately before the deploy step**. Not in a separate job the deploy job `needs`, and not at the top of the pipeline: a freeze announced while the build runs would be missed entirely, and the deploy would go out into it. What matters is the gap between the answer and the deployment.

If the build is long enough that you want to fail fast, call it **twice** — once at the start with `FREEZEHUB_ON_ERROR=allow`, and again before the deploy step at the default `block`. The early call is an optimisation and should never be what fails your pipeline; the late one is the gate. Evaluations are unlimited on every plan, so the second call costs nothing.

Do not add `continue-on-error`; it turns every freeze into a warning.

The `GITHUB_*` variables are passed through so the check is recorded against a person and a commit rather than "some pipeline". They are optional — omit them and the gate still works, the record is just less useful afterwards.

## GitLab CI

```yaml
freeze-check:production:
  stage: freeze-check
  image: ghcr.io/freezehubio/freeze-check:v1
  script: [freeze-check]
  variables:
    FREEZEHUB_URL: https://freezehub.example.com
    FREEZEHUB_APPLICATION: payments-api
    FREEZEHUB_ENVIRONMENT: production
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH

deploy:production:
  needs: ["freeze-check:production"]
  script: [./deploy.sh]
```

Set `FREEZEHUB_API_KEY` in Settings → CI/CD → Variables, **Masked and Protected**. Never as a job variable in the file — that is a credential in your repository.

`needs:` is what enforces the gate: a failed check fails the pipeline before the deploy job is created. Do not add `allow_failure: true`.

GitLab Runner overrides the image's entrypoint and runs its own shell, which is why the gate is on `PATH` as `freeze-check` and not only the entrypoint. `CI_COMMIT_SHA`, `GITLAB_USER_EMAIL` and `CI_PIPELINE_URL` are already in the job environment and are picked up automatically.

The check runs only on the deploying branch by default. Asking on every feature branch would fail merge-request pipelines during a freeze, which is not the point.

## Jenkins

```groovy
stage('FreezeHub check') {
  steps {
    withCredentials([string(credentialsId: 'freezehub-api-key', variable: 'FREEZEHUB_API_KEY')]) {
      sh '''
        docker run --rm \
          -e FREEZEHUB_URL=https://freezehub.example.com \
          -e FREEZEHUB_API_KEY \
          -e FREEZEHUB_APPLICATION=payments-api \
          -e FREEZEHUB_ENVIRONMENT=production \
          -e FREEZEHUB_ACTOR="${BUILD_USER_EMAIL:-jenkins}" \
          -e FREEZEHUB_REFERENCE="${GIT_COMMIT}" \
          -e FREEZEHUB_SOURCE="${BUILD_URL}" \
          ghcr.io/freezehubio/freeze-check:v1
      '''
    }
  }
}
```

`withCredentials` rather than a pipeline literal, so the key never reaches the build log. Do not wrap the step in `catchError` — a failed check must fail the build.

Jenkins exposes no standard "who started this" variable, so `FREEZEHUB_ACTOR` is set explicitly; `BUILD_USER_EMAIL` comes from the Build User Vars plugin if you have it.

## Argo CD

A `PreSync` hook, so the check runs before the sync it is gating:

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: freeze-check
  annotations:
    argocd.argoproj.io/hook: PreSync
    argocd.argoproj.io/hook-delete-policy: HookSucceeded
spec:
  backoffLimit: 0            # a freeze is not a transient error; do not retry inside the hook
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: freeze-check
          image: ghcr.io/freezehubio/freeze-check:v1
          env:
            - name: FREEZEHUB_URL
              value: https://freezehub.example.com
            - name: FREEZEHUB_APPLICATION
              value: payments-api
            - name: FREEZEHUB_ENVIRONMENT
              value: production
            - name: FREEZEHUB_API_KEY
              valueFrom:
                secretKeyRef: { name: freezehub, key: api-key }
```

**Argo is different in kind from the others, and it is worth understanding before you turn it on.** They gate a step in a pipeline; this gates a *sync*, and by the time Argo syncs, the change is already committed and merged. With auto-sync enabled, the freeze is the only thing standing between a merged commit and production — the strongest form of the product, and the most surprising.

A failed hook fails the sync and leaves the Application `OutOfSync`. **Argo will retry on its own schedule**, so during a freeze it will fail repeatedly, by design. Tell whoever watches your alerts, or they will page someone at 3am for a working freeze.

## Anything else

CircleCI, Buildkite, Tekton, Azure Pipelines, Bitbucket, Drone, Concourse — all the same shape:

```bash
docker run --rm \
  -e FREEZEHUB_URL -e FREEZEHUB_API_KEY \
  -e FREEZEHUB_APPLICATION=payments-api \
  -e FREEZEHUB_ENVIRONMENT=production \
  ghcr.io/freezehubio/freeze-check:v1
```

No container runtime? Run the script directly — it is POSIX shell and needs only `curl` and `jq`. `examples/` shows that route.

---

## The reference implementations

[`action.yml`](./action.yml) and [`templates/freeze-check.yml`](./templates/freeze-check.yml) are a real GitHub Action and a real GitLab CI/CD component, and both are covered by the test suite.

**They resolve now, and are still not offered.** Until this repository moved into the `freezehubio` organization and became public, `uses:` and `component:` could not resolve against it at all — that was the reason given here, and it has gone. What stands in its place is `D-26`: the image is the connector, so publishing one artifact makes every guideline work at once, while a per-ecosystem package is a second thing to keep in step for one ecosystem's benefit. A Marketplace or Catalog listing is a separate and lesser question (`FZ-096`).

They are kept because they cost nothing to keep, they stay honest by being tested, and they are ready the day that changes.

## Tests

```bash
node connectors/test/run-tests.js              # behaviour — the script and both references
node connectors/test/check-connectors.js       # their shape
docker build -t freeze-check:test connectors/
sh connectors/test/image-smoke.sh freeze-check:test
```

`run-tests.js` drives the script against a stub Policy API, and most of what it asserts is the set of rules that must **not** fail open. Each is one `case` branch away from turning a freeze into a warning for every customer at once, and the failure is silent: the pipeline deploys and reports success.

It also executes the action and the GitLab job, with the environment read out of `action.yml` and `templates/freeze-check.yml` rather than restated, so the two cannot drift.

`image-smoke.sh` covers what packaging can break — that busybox `ash` runs the script, that the exit code survives the container boundary, that the gate is on `PATH` when the entrypoint is overridden the way GitLab Runner overrides it, and that it is not root. It needs no network, so it behaves the same on a laptop and on a CI runner.
