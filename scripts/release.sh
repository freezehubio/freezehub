#!/bin/sh
#
# Release FreezeHub to the single box, in one command (FZ-198).
#
#   ./scripts/release.sh                  # backend and frontend, from HEAD
#   ./scripts/release.sh --backend-only
#   ./scripts/release.sh --frontend-only
#   ./scripts/release.sh --image-tag 9c1f0aa   # redeploy or roll back; skips the build
#   ./scripts/release.sh --dry-run        # print the dispatches, send nothing
#
# Requires: gh (authenticated). AWS credentials only if you want the instance id
# resolved for you; see --instance-id below.
#
#
# WHAT THIS DOES NOT DO, AND WHY THAT IS THE POINT
#
# It does not deploy. It dispatches the three workflows in `docs/14-operations.md`
# § Releasing and waits for each — Build image, Deploy single-box, Deploy frontend —
# because everything that makes a deploy safe lives inside them:
#
#   - FreezeHub asks FreezeHub (FZ-182). Deploy single-box runs ./connectors, the same
#     gate customers run, against our own Policy API. A freeze in force stops our deploy
#     exactly as it stops theirs. A script that shelled out to SSM would walk around the
#     one control this product exists to provide.
#   - No credentials leave the laptop. The workflows assume a role by OIDC; there is no
#     access key here to lose. Deploying locally would need one.
#   - The deploy is attributable. SSM Run Command is an IAM-authorised call in CloudTrail,
#     with an actor. A local deploy is attributable to whoever's shell it was.
#   - `concurrency: deploy-singlebox` stops two releases racing. Two laptops cannot.
#   - The moving-tag refusal, and the wait for the *real* SSM outcome rather than for the
#     API accepting the request, are already written there and already tested.
#
# So the slow part of a release was never the safety. It was finding two values by hand —
# the tag printed by one run, the instance id from `terraform output` — and three trips
# through the Actions UI. That is what this removes.
#
# Rollback is this script with --image-tag set to an earlier tag. It skips the build and
# sends the old image, which is what the runbook already tells you to do by hand.

set -eu

ENVIRONMENT="${FREEZEHUB_ENVIRONMENT:-beta}"
INSTANCE_ID="${FREEZEHUB_INSTANCE_ID:-}"
IMAGE_TAG=""
DO_BACKEND=1
DO_FRONTEND=1
DRY_RUN=0
REPO="freezehubio/freezehub"

usage() {
    sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

fail() {
    echo "release: $1" >&2
    exit 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --backend-only)  DO_FRONTEND=0 ;;
        --frontend-only) DO_BACKEND=0 ;;
        --image-tag)     IMAGE_TAG="${2:?--image-tag needs a value}"; shift ;;
        --instance-id)   INSTANCE_ID="${2:?--instance-id needs a value}"; shift ;;
        --environment)   ENVIRONMENT="${2:?--environment needs a value}"; shift ;;
        --dry-run)       DRY_RUN=1 ;;
        -h|--help)       usage 0 ;;
        *)               echo "release: unknown argument '$1'" >&2; usage 1 ;;
    esac
    shift
done

command -v gh >/dev/null 2>&1 || fail "gh is not installed. This script drives GitHub Actions."
gh auth status >/dev/null 2>&1 || fail "gh is not authenticated. Run: gh auth login"

run() {
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "  would run: $*"
        return 0
    fi
    "$@"
}

# ── What is being released ────────────────────────────────────────────────────────────
#
# The build workflow builds the ref it is dispatched on, so the thing that reaches the box
# is whatever that commit contains — not what is in front of you. Two guards, because the
# difference is invisible at the moment it matters and obvious a week later.

SHA=$(git rev-parse HEAD)
SHORT=$(git rev-parse --short HEAD)

if [ -n "$(git status --porcelain)" ]; then
    echo "release: the working tree is dirty. Those changes are NOT in the release —" >&2
    echo "         the build takes $SHORT from the remote, not your working copy." >&2
    printf "         Continue? [y/N] " >&2
    read -r reply
    case "$reply" in y|Y) ;; *) fail "stopped." ;; esac
fi

if [ -z "$IMAGE_TAG" ]; then
    git fetch --quiet origin
    # `branch -r --contains` is the question that matters: not "is this pushed somewhere"
    # but "can Actions check this out". A commit only in a local branch cannot be built.
    if [ -z "$(git branch -r --contains "$SHA" 2>/dev/null)" ]; then
        fail "$SHORT is not on origin. Push it first — Actions can only build what it can fetch."
    fi
fi

# ── The instance ──────────────────────────────────────────────────────────────────────
#
# Deploy single-box takes the instance id as an input rather than a repository variable,
# so it has to come from somewhere. Terraform state is the authority; the EC2 tag is the
# fallback for a laptop without terraform; and FREEZEHUB_INSTANCE_ID or --instance-id is
# the answer for a laptop with neither.

resolve_instance() {
    if [ -n "$INSTANCE_ID" ]; then
        echo "$INSTANCE_ID"
        return 0
    fi
    if command -v terraform >/dev/null 2>&1; then
        terraform -chdir=infra/singlebox output -raw instance_id 2>/dev/null && return 0
    fi
    if command -v aws >/dev/null 2>&1; then
        aws ec2 describe-instances \
            --filters "Name=tag:Name,Values=freezehub-${ENVIRONMENT}" \
                      "Name=instance-state-name,Values=running" \
            --query 'Reservations[].Instances[].InstanceId' --output text 2>/dev/null \
            | awk '{print $1}' | grep . && return 0
    fi
    return 1
}

# The guard FZ-175 paid for. An apply went into the operator's personal account and
# nothing stopped it, because nothing asserted which account the credentials belonged to.
# Terraform now refuses on `allowed_account_ids`; anything else reaching for AWS should
# make the same assertion rather than trusting the ambient profile. Only relevant when we
# actually touch AWS — the workflows use OIDC and never see these credentials.
check_account() {
    expected=$(sed -n 's/^ *aws_account_id *= *"\{0,1\}\([0-9]\{12\}\)"\{0,1\}.*/\1/p' \
        infra/singlebox/terraform.tfvars 2>/dev/null | head -1)
    [ -n "$expected" ] || return 0
    command -v aws >/dev/null 2>&1 || return 0
    actual=$(aws sts get-caller-identity --query Account --output text 2>/dev/null) || return 0
    [ "$actual" = "$expected" ] || fail "AWS credentials are for account $actual, not $expected.
         This is the FZ-175 failure: the wrong account, silently. Fix the profile, or pass
         --instance-id so this script never reaches for AWS at all."
}

# ── Driving a workflow ────────────────────────────────────────────────────────────────
#
# `gh workflow run` does not return the run it created, so the id has to be found. Match
# on the commit AND on having started after the dispatch, or a re-run of this morning's
# build gets watched instead of the one just asked for.

latest_run_id() {
    workflow="$1"; started_after="$2"
    gh run list --repo "$REPO" --workflow "$workflow" --limit 20 \
        --json databaseId,headSha,createdAt \
        --jq "[.[] | select(.headSha == \"$SHA\" and .createdAt >= \"$started_after\")]
              | sort_by(.createdAt) | last | .databaseId" 2>/dev/null
}

dispatch_and_wait() {
    workflow="$1"; shift
    echo "→ $workflow"
    started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    run gh workflow run "$workflow" --repo "$REPO" --ref "$SHA" "$@" || fail "could not dispatch $workflow"
    [ "$DRY_RUN" -eq 1 ] && { RUN_ID=""; return 0; }

    RUN_ID=""
    i=0
    while [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; do
        i=$((i + 1))
        [ "$i" -gt 30 ] && fail "dispatched $workflow but never saw the run. Check the Actions tab."
        sleep 2
        RUN_ID=$(latest_run_id "$workflow" "$started_at")
    done

    echo "  https://github.com/$REPO/actions/runs/$RUN_ID"
    # --exit-status makes a failed workflow fail this script, so a broken build never
    # reaches the deploy step.
    gh run watch "$RUN_ID" --repo "$REPO" --exit-status >/dev/null \
        || fail "$workflow failed. Open the run above; for a deploy see docs/14-operations.md."
    echo "  ok"
}

# ── The release ───────────────────────────────────────────────────────────────────────

echo "FreezeHub release — $ENVIRONMENT — $SHORT"
[ "$DRY_RUN" -eq 1 ] && echo "(dry run: nothing will be dispatched)"

if [ "$DO_BACKEND" -eq 1 ]; then
    check_account
    INSTANCE_ID=$(resolve_instance) || fail "could not work out the instance id.
         Pass --instance-id, set FREEZEHUB_INSTANCE_ID, or run from a checkout with
         terraform and credentials:  terraform -chdir=infra/singlebox output -raw instance_id"
    echo "  instance $INSTANCE_ID"

    if [ -z "$IMAGE_TAG" ]; then
        dispatch_and_wait build-image.yml -f environment="$ENVIRONMENT"
        if [ "$DRY_RUN" -eq 0 ]; then
            # The tag the workflow actually pushed, read back from its own output rather
            # than recomputed here: `git rev-parse --short` can abbreviate to a different
            # length on a different machine, and a tag that is nearly right is a deploy of
            # nothing.
            IMAGE_TAG=$(gh run view "$RUN_ID" --repo "$REPO" --log 2>/dev/null \
                | sed -n 's/.*image_tag=\([0-9a-zA-Z._-]\{4,\}\).*/\1/p' | head -1)
            [ -n "$IMAGE_TAG" ] || fail "the build succeeded but did not print an image_tag. Deploy by hand."
            echo "  built $IMAGE_TAG"
        else
            IMAGE_TAG="$SHORT"
        fi
    else
        echo "  using existing image $IMAGE_TAG (no build)"
    fi

    dispatch_and_wait deploy-singlebox.yml \
        -f image_tag="$IMAGE_TAG" -f instance_id="$INSTANCE_ID"
fi

if [ "$DO_FRONTEND" -eq 1 ]; then
    dispatch_and_wait deploy-frontend.yml -f environment="$ENVIRONMENT"
fi

echo
echo "Released."
[ "$DO_BACKEND" -eq 1 ] && echo "  backend   $IMAGE_TAG on $INSTANCE_ID"
[ "$DO_FRONTEND" -eq 1 ] && echo "  frontend  published to $ENVIRONMENT"
echo "  roll back: ./scripts/release.sh --backend-only --image-tag <earlier tag>"
echo "  runbook:   docs/14-operations.md"
