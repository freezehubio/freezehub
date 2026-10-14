#!/bin/sh
# Landing page request counts from CloudFront's always-on CloudWatch metrics.
export AWS_PROFILE=admin
DIST=${FREEZEHUB_DISTRIBUTION_ID:-E1UE997VT1TXCT}
DAYS=${1:-30}

echo "distribution $DIST — last $DAYS days"
echo

aws cloudwatch get-metric-statistics \
  --namespace AWS/CloudFront --metric-name Requests \
  --dimensions Name=DistributionId,Value="$DIST" Name=Region,Value=Global \
  --start-time "$(date -u -v-"${DAYS}"d +%Y-%m-%dT00:00:00Z)" \
  --end-time "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --period 86400 --statistics Sum --region us-east-1 \
  --query 'sort_by(Datapoints,&Timestamp)[].[Timestamp,Sum]' \
  --output text \
| awk '{ printf "%s  %8d\n", substr($1,1,10), $2; t+=$2; d++ }
       END { if (d) printf "\n%d days with traffic, %d requests total, %.0f/day avg\n", d, t, t/d;
             else print "no datapoints — nothing reached the distribution in this window" }'
