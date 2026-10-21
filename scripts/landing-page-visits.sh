#!/bin/sh
# Daily REQUEST counts from CloudFront's always-on CloudWatch metrics (FZ-230).
#
# These are requests, not visits. One page load is about four of them, the figure is
# per-distribution rather than per-path, and because the distribution rewrites 403 and 404
# to 200 /index.html a scanner counts the same as a reader. Good for a trend; useless for
# attribution. For that, use landing-page-logs.sh.
#
# Days are UTC, so they line up with the access logs.
export AWS_PROFILE=admin
DIST=${FREEZEHUB_DISTRIBUTION_ID:-E1UE997VT1TXCT}
DAYS=${1:-30}

echo "distribution $DIST — last $DAYS days"
echo

# An expired session and a genuinely quiet week both produce no datapoints. Deciding which
# is which from the output alone is impossible, so check the credential first and refuse to
# report rather than report nothing and let it read as silence.
if ! aws sts get-caller-identity >/dev/null 2>&1; then
  echo "Cannot reach AWS: the session has expired or the profile is wrong." >&2
  echo "Run: aws login --profile admin" >&2
  exit 2
fi

# The CLI renders each bucket's timestamp in LOCAL time with an offset:
# 2026-10-14T19:00:00-05:00 is the bucket that begins 2026-10-15T00:00:00Z. Taking the
# first ten characters labelled every day one early, which is what this did until FZ-234.
# The formatter below parses the offset and converts, so a day here is the same day the
# logs report.
aws cloudwatch get-metric-statistics \
  --namespace AWS/CloudFront --metric-name Requests \
  --dimensions Name=DistributionId,Value="$DIST" Name=Region,Value=Global \
  --start-time "$(date -u -v-"${DAYS}"d +%Y-%m-%dT00:00:00Z)" \
  --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --period 86400 --statistics Sum --region us-east-1 \
  --query 'sort_by(Datapoints,&Timestamp)[].[Timestamp,Sum]' \
  --output text \
| node -e '
let s = "";
process.stdin.on("data", (d) => (s += d)).on("end", () => {
  const rows = s.trim().split("\n").filter(Boolean).map((l) => {
    const [ts, n] = l.split(/\s+/);
    return [new Date(ts).toISOString().slice(0, 10), Number(n)];
  });
  if (!rows.length) {
    console.log("No datapoints in this window. The credential worked, so this is genuine silence.");
    return;
  }
  let total = 0;
  for (const [day, n] of rows) {
    console.log(day + "  " + String(n).padStart(8));
    total += n;
  }
  console.log("");
  console.log(rows.length + " days with traffic, " + total + " requests total, "
              + Math.round(total / rows.length) + "/day average");
  console.log("(UTC days, matching the access logs)");
});
'
