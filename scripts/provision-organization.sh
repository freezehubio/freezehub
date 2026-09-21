#!/bin/sh
#
# Turns a demo into a customer (FZ-086).
#
# Creates an organization, puts it on an agreed plan, gives it a first Administrator, and
# marks the demo request it came from as converted.
#
#   scripts/provision-organization.sh --company "Northwind" \
#                                     --admin dana@northwind.test \
#                                     --plan GROWTH \
#                                     [--demo-request 7] \
#                                     [--applications 250]
#
# A SCRIPT AND NOT AN ADMIN CONSOLE, deliberately (D-23). The security model of this
# product is one sentence — the organization is always resolved from the credential, never
# from the request — and an in-product principal able to act across tenants is the exact
# negation of it. At this volume a script run by an operator is safer and cheaper, and it
# is reviewable in git.
#
# WHAT THIS NEEDS, and who can run it
# -----------------------------------
# Whoever runs this is not necessarily whoever wrote it, so:
#
#   * Database access. The first user of an organization cannot be created through the
#     API — there is no self-service signup (06-security.md), and inviting someone needs
#     an Administrator who does not exist yet. So the organization, that first user and
#     the subscription are inserted directly. Everything after the first Administrator
#     goes through the API.
#
#   * The backend reachable at FREEZEHUB_URL, and a psql that can reach its database.
#     Locally both come from docker compose. In a deployed environment that means an
#     operator with production database access, which is exactly the cost D-23 accepted.
#
#   * THE COGNITO POOL, against a deployed environment. Pass --user-pool-id (or set
#     COGNITO_USER_POOL_ID) and the script creates the Administrator's identity and stores
#     the sub Cognito issues, so they can sign in. Omit it and it does not, which is right
#     under the `local` profile and wrong everywhere else — it says which it did.
#
#     This used to be impossible (OI-2), and the script invented a subject and admitted at
#     the end that the person could never sign in. FZ-046 closed that; FZ-177 caught up.
#
# Requires: curl, jq, docker compose (for psql), and a running backend.

set -eu

FREEZEHUB_URL="${FREEZEHUB_URL:-http://localhost:8099}"
COMPANY=""
ADMIN_EMAIL=""
PLAN=""
DEMO_REQUEST=""
APPLICATION_LIMIT=""
# Empty means "there is no identity provider", which is true under the `local` profile and
# wrong anywhere else (FZ-177). See "the identity the API cannot create" below.
USER_POOL_ID="${COGNITO_USER_POOL_ID:-}"
AWS_REGION="${AWS_REGION:-us-east-2}"

say()  { printf '\n\033[1m%s\033[0m\n' "$1"; }
note() { printf '  %s\n' "$1"; }
fail() { printf '%s\n' "$1" >&2; exit 1; }

usage() {
    fail "usage: provision-organization.sh --company NAME --admin EMAIL --plan PLAN
                                   [--demo-request ID] [--applications N]
                                   [--user-pool-id ID] [--region REGION]

  --plan          STARTER | GROWTH | SCALE | ENTERPRISE
  --demo-request  the demo_request row this closes, marked CONVERTED
  --applications  application limit override; ENTERPRISE only, since every other
                  plan's limit is a published number and not a per-deal one
  --user-pool-id  the Cognito pool to create the Administrator's identity in, so they
                  can actually sign in. Also read from COGNITO_USER_POOL_ID. Omit it
                  only against a backend running the 'local' profile, where no pool
                  exists and a fabricated subject is correct
  --region        the pool's region; also AWS_REGION (default us-east-2)"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --company)      COMPANY="${2:-}"; shift 2 ;;
        --admin)        ADMIN_EMAIL="${2:-}"; shift 2 ;;
        --plan)         PLAN="${2:-}"; shift 2 ;;
        --demo-request) DEMO_REQUEST="${2:-}"; shift 2 ;;
        --applications) APPLICATION_LIMIT="${2:-}"; shift 2 ;;
        --user-pool-id) USER_POOL_ID="${2:-}"; shift 2 ;;
        --region)       AWS_REGION="${2:-}"; shift 2 ;;
        -h|--help)      usage ;;
        *)              fail "Unknown argument: $1" ;;
    esac
done

[ -n "$COMPANY" ]     || usage
[ -n "$ADMIN_EMAIL" ] || usage
[ -n "$PLAN" ]        || usage

case "$PLAN" in
    STARTER|GROWTH|SCALE|ENTERPRISE) ;;
    # TRIAL is deliberately not provisionable: a trial is something an organization starts
    # for itself at signup (FZ-082), not something sales hands out.
    *) fail "Plan must be STARTER, GROWTH, SCALE or ENTERPRISE — got '$PLAN'." ;;
esac

if [ -n "$APPLICATION_LIMIT" ] && [ "$PLAN" != "ENTERPRISE" ]; then
    fail "--applications applies to ENTERPRISE only.

Every other plan's application limit is a published number (11-commercial.md). Overriding
one here would mean a customer paying for Starter with a limit nobody can look up, and the
pricing page quietly becoming untrue."
fi

command -v curl >/dev/null 2>&1 || fail "curl is not installed."
command -v jq   >/dev/null 2>&1 || fail "jq is not installed."

curl -sf "$FREEZEHUB_URL/actuator/health" >/dev/null 2>&1 || fail \
"No backend at $FREEZEHUB_URL.

  docker compose up -d postgres
  cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"

psql() { docker compose exec -T postgres psql -U freezehub -d freezehub "$@"; }
scalar() { psql -tAc "$1" | tr -d '[:space:]'; }

# Single-quotes are the whole risk in a script that builds SQL: a company called
# O'Brien Ltd would otherwise end the string and run whatever came next. Doubling them is
# the SQL escape, and it is applied to every value that reaches a statement below.
sql_quote() { printf '%s' "$1" | sed "s/'/''/g"; }

COMPANY_SQL=$(sql_quote "$COMPANY")
ADMIN_SQL=$(sql_quote "$ADMIN_EMAIL")

# --- refuse to run twice ----------------------------------------------------------------
#
# Two ways this could duplicate, so both are checked. Provisioning the same customer twice
# gives them two organizations, and the one they are told about is not necessarily the one
# their pipeline authenticates into.
EXISTING_ORG=$(scalar "SELECT id FROM organization WHERE name = '$COMPANY_SQL' LIMIT 1")
[ -z "$EXISTING_ORG" ] || fail \
"An organization named '$COMPANY' already exists (id $EXISTING_ORG).

If this is a second organization for the same customer, give it a distinct name. If the
first attempt failed part-way, inspect it before running this again."

EXISTING_USER=$(scalar "SELECT organization_id FROM users WHERE lower(email) = lower('$ADMIN_SQL') LIMIT 1")
[ -z "$EXISTING_USER" ] || fail \
"$ADMIN_EMAIL already belongs to organization $EXISTING_USER.

A person exists once. Inviting them into a second organization is a different feature, and
not one this product has."

if [ -n "$DEMO_REQUEST" ]; then
    REQUEST_STATUS=$(scalar "SELECT status FROM demo_request WHERE id = $DEMO_REQUEST")
    [ -n "$REQUEST_STATUS" ] || fail "There is no demo request with id $DEMO_REQUEST."
    [ "$REQUEST_STATUS" != "CONVERTED" ] || fail \
"Demo request $DEMO_REQUEST is already marked CONVERTED. It has been provisioned once."
fi

# --- the identity the API cannot create -------------------------------------------------
#
# FZ-177. Before this, external_subject was invented here — 'provisioned-<id>-<md5>' — and
# no Cognito user was made, so the first Administrator of every organization could never
# sign in. The script said so at the end and blamed OI-2, which FZ-046 has since closed.
#
# Cognito assigns the subject. It is not the email, not the username, not anything this
# script can compute, so it has to come back from AdminCreateUser and be stored verbatim.
#
# DELIBERATELY BEFORE THE TRANSACTION. If this fails, nothing has been written and the
# operator can simply run the script again. The reverse order would leave an organization
# whose Administrator cannot sign in — which is the bug being fixed.
if [ -n "$USER_POOL_ID" ]; then
    say "Creating the Cognito identity for $ADMIN_EMAIL"

    command -v aws >/dev/null 2>&1 || fail \
"--user-pool-id was given but the aws CLI is not on PATH. It is what creates the identity."

    # email_verified, because whoever ran this has already vouched for the address; without
    # it Cognito holds it unverified and password recovery has nowhere to go.
    CREATED=$(aws cognito-idp admin-create-user \
        --user-pool-id "$USER_POOL_ID" \
        --username "$ADMIN_EMAIL" \
        --region "$AWS_REGION" \
        --user-attributes "Name=email,Value=$ADMIN_EMAIL" "Name=email_verified,Value=true" \
        --output json 2>&1) || fail \
"Could not create the Cognito identity, so nothing was written to the database:

$CREATED"

    EXTERNAL_SUBJECT=$(printf '%s' "$CREATED" \
        | jq -r '.User.Attributes[]? | select(.Name == "sub") | .Value')

    [ -n "$EXTERNAL_SUBJECT" ] || fail \
"Cognito created the user but returned no sub. A row written now would look correct and
belong to nobody. Delete the identity for $ADMIN_EMAIL in pool $USER_POOL_ID before
running this again."

    note "cognito sub   $EXTERNAL_SUBJECT"
    SIGN_IN_NOTE="They can sign in. Cognito emailed a temporary password to $ADMIN_EMAIL,
      which it will require them to change on first use."
else
    # No pool, so no identity: correct under the `local` profile, where LocalIdentityProvider
    # invents subjects too and /api/dev/token accepts any email. Wrong anywhere else, which
    # is what the summary at the end says out loud.
    EXTERNAL_SUBJECT="provisioned-$(date +%s)-$$"
    note "no --user-pool-id: using a local-only subject, and this Administrator cannot sign in"
    SIGN_IN_NOTE="They CANNOT sign in. No --user-pool-id was given, so no Cognito identity
      exists and the stored subject matches nothing. Correct only if this backend runs the
      \"local\" profile; otherwise re-run against the pool."
fi

SUBJECT_SQL=$(sql_quote "$EXTERNAL_SUBJECT")

# --- the rows the API cannot create -----------------------------------------------------
say "Creating $COMPANY on $PLAN"

# One statement, so a failure part-way leaves nothing behind. An organization with no
# administrator is unreachable, and one with no subscription is unbilled — both are worse
# than not having run at all.
psql >/dev/null <<SQL
BEGIN;

WITH org AS (
  INSERT INTO organization (name) VALUES ('$COMPANY_SQL') RETURNING id
), admin AS (
  INSERT INTO users (organization_id, external_subject, email, role)
  SELECT id, '$SUBJECT_SQL', '$ADMIN_SQL', 'ADMINISTRATOR'
  FROM org
)
INSERT INTO subscription (organization_id, plan, status, application_limit_override)
SELECT id, '$PLAN', 'ACTIVE', $( [ -n "$APPLICATION_LIMIT" ] && printf '%s' "$APPLICATION_LIMIT" || printf 'NULL' )
FROM org;

COMMIT;
SQL

ORGANIZATION_ID=$(scalar "SELECT id FROM organization WHERE name = '$COMPANY_SQL'")
[ -n "$ORGANIZATION_ID" ] || fail "Provisioning did not create an organization. Nothing was committed."

note "organization  $ORGANIZATION_ID"
note "administrator $ADMIN_EMAIL"
note "plan          $PLAN${APPLICATION_LIMIT:+ (limit $APPLICATION_LIMIT applications)}"

# --- close the loop on the demo request -------------------------------------------------
if [ -n "$DEMO_REQUEST" ]; then
    say "Marking demo request $DEMO_REQUEST converted"
    psql >/dev/null <<SQL
UPDATE demo_request
   SET status = 'CONVERTED', converted_organization_id = $ORGANIZATION_ID, updated_at = now()
 WHERE id = $DEMO_REQUEST;
SQL
    note "demo request $DEMO_REQUEST -> organization $ORGANIZATION_ID"
fi

# --- confirm it is actually usable ------------------------------------------------------
#
# Read back through the API rather than trusting the inserts. It is the only check that
# proves the organization resolves from a credential, which is what every other request
# will depend on.
say "Verifying"

TOKEN=$(curl -sf -X POST "$FREEZEHUB_URL/api/dev/token" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\"}" 2>/dev/null | jq -r '.token // empty')

if [ -n "$TOKEN" ]; then
    PLAN_SEEN=$(curl -sf "$FREEZEHUB_URL/api/billing/subscription" \
        -H "Authorization: Bearer $TOKEN" | jq -r '.plan // empty')
    [ "$PLAN_SEEN" = "$PLAN" ] || fail \
"The organization was created but reports plan '$PLAN_SEEN' rather than '$PLAN'. Inspect it."
    note "signed in and read back plan $PLAN_SEEN"
else
    # /api/dev/token only exists under the `local` profile, so this is the expected path in
    # a deployed environment rather than a failure.
    note "could not mint a token — expected unless this backend runs the local profile"
fi

say "Done"
cat <<SUMMARY
  $COMPANY is provisioned on $PLAN, and $ADMIN_EMAIL is its Administrator.

  Before telling the customer:

    * ${SIGN_IN_NOTE}

    * Further users are invited in-product by the Administrator, not by running this
      again — POST /api/invites, or Settings in the UI.
SUMMARY
