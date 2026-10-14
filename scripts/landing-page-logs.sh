#!/bin/sh
# What CloudFront's access logs say about who visited (FZ-230).
#
# Field numbers are from CloudFront's standard log format, which is tab-separated:
#
#   1 date  2 time  3 x-edge-location  4 sc-bytes  5 c-ip  6 cs-method  7 cs(Host)
#   8 cs-uri-stem  9 sc-status  10 cs(Referer)  11 cs(User-Agent)  12 cs-uri-query
#
# There is NO country field in standard logs — that exists only in real-time logs, which
# cost money. `x-edge-location` is the nearest thing: the edge that served the request, so
# BOG50 is Bogotá and LHR3 is London. It locates the edge, not the person.
#
# Delivery is not immediate. AWS says within 24 hours and it is usually far sooner, so an
# empty result shortly after a visit means "not yet", not "nobody came".
export AWS_PROFILE=admin
BUCKET=freezehub-beta-frontend-logs-668471252983
FILES=${1:-20}

n=$(aws s3 ls "s3://$BUCKET/cloudfront/" 2>/dev/null | wc -l | tr -d ' ')
echo "log files delivered: $n"
[ "$n" = "0" ] && { echo "Nothing yet. Re-run in an hour."; exit 0; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
aws s3 ls "s3://$BUCKET/cloudfront/" | tail -"$FILES" | awk '{print $4}' | while read -r f; do
  aws s3 cp "s3://$BUCKET/cloudfront/$f" "$tmp/$f" --quiet
done
gzcat "$tmp"/*.gz 2>/dev/null | grep -v '^#' > "$tmp/all.tsv"
echo "requests in the last $FILES files: $(wc -l < "$tmp/all.tsv" | tr -d ' ')"

echo
echo "--- pages asked for (ignoring assets) ---"
awk -F'\t' '$8 !~ /^\/(assets|favicon)/ {print $8}' "$tmp/all.tsv" | sort | uniq -c | sort -rn | head -10

echo
echo "--- edge locations, a rough guide to where from ---"
awk -F'\t' '{print substr($3,1,3)}' "$tmp/all.tsv" | sort | uniq -c | sort -rn | head -8

echo
echo "--- EXTERNAL referrers: anyone arriving from somewhere that is not us ---"
ext=$(awk -F'\t' '$10 != "-" && $10 !~ /app\.freezehub\.io/ {print $10}' "$tmp/all.tsv" | sort | uniq -c | sort -rn)
[ -n "$ext" ] && echo "$ext" || echo "  none — every referrer is either absent or our own pages"

echo
echo "--- obvious bots, so they are not mistaken for readers ---"
awk -F'\t' 'tolower($11) ~ /bot|crawl|spider|virustotal|curl|wget|scan/ {print $11}' "$tmp/all.tsv" \
  | sed 's/%20/ /g' | cut -c1-60 | sort | uniq -c | sort -rn | head -6

echo
echo "--- everything else, by user agent ---"
awk -F'\t' 'tolower($11) !~ /bot|crawl|spider|virustotal|curl|wget|scan/ {print $11}' "$tmp/all.tsv" \
  | sed 's/%20/ /g' | cut -c1-70 | sort | uniq -c | sort -rn | head -6
