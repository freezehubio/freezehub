#!/bin/sh
# What CloudFront's access logs say about who visited (FZ-230, restructured by FZ-234).
#
# Downloads the most recent log files and hands them to traffic-report.js, which does the
# judging. Field positions are read from the log's own `#Fields:` header rather than
# assumed — hard-coding them is what made an earlier version print user agents under a
# heading that said "referrers".
#
# Delivery is not immediate. AWS says within 24 hours and it is usually far sooner, so an
# empty result shortly after a visit means "not yet", not "nobody came".
#
#   ./scripts/landing-page-logs.sh        # the last 40 log files
#   ./scripts/landing-page-logs.sh 200    # a wider window
export AWS_PROFILE=admin
BUCKET=freezehub-beta-frontend-logs-668471252983
FILES=${1:-40}
HERE=$(cd "$(dirname "$0")" && pwd)

# "No log files" and "no credentials" look identical once stderr is discarded, and the
# second must never be reported as the first — an empty listing would read as "nobody came".
if ! aws sts get-caller-identity >/dev/null 2>&1; then
  echo "Cannot reach AWS: the session has expired or the profile is wrong." >&2
  echo "Run: aws login --profile admin" >&2
  exit 2
fi

listing=$(aws s3 ls "s3://$BUCKET/cloudfront/" 2>/dev/null)
n=$(printf '%s\n' "$listing" | grep -c '\.gz$')
if [ "$n" -eq 0 ]; then
  echo "No log files delivered yet. CloudFront can take up to an hour; re-run later."
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
printf '%s\n' "$listing" | grep '\.gz$' | tail -"$FILES" | awk '{print $4}' | while read -r f; do
  aws s3 cp "s3://$BUCKET/cloudfront/$f" "$tmp/$f" --quiet
done

echo "$n log files delivered; reading the most recent $FILES."
gzcat "$tmp"/*.gz 2>/dev/null | node "$HERE/traffic-report.js"
