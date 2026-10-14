# CloudFront access logs for the frontend distribution (`FZ-230`).
#
# The question this exists to answer is "did anyone read it, and where did they come from".
# The `Requests` metric in CloudWatch cannot: it counts requests rather than visits, it is
# per-distribution rather than per-path, and because the distribution rewrites 403 and 404 to
# `200 /index.html` for SPA routing, a scanner probing `/wp-login.php` is indistinguishable
# from a prospect reading the pitch. These logs carry the path, the referrer, the user agent
# and the country, which is the difference between knowing and guessing.
#
# **Not retroactive.** Logging delivers what happens after it is enabled and nothing before,
# which is why this landed the day the first outreach batch went out rather than after it.

variable "frontend_log_retention_days" {
  description = "How long CloudFront access logs are kept. They contain IP addresses, so this is a privacy decision as much as a cost one."
  type        = number
  default     = 90
}

data "aws_canonical_user_id" "current" {}

resource "aws_s3_bucket" "frontend_logs" {
  bucket = "${local.name}-frontend-logs-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "frontend_logs" {
  bucket = aws_s3_bucket.frontend_logs.id

  # These logs are personal data — an IP address identifies a person under Ley 1581 as much
  # as under the GDPR. Nothing about them should ever be reachable without credentials.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend_logs" {
  bucket = aws_s3_bucket.frontend_logs.id

  rule {
    apply_server_side_encryption_by_default {
      # AES256 rather than KMS deliberately: CloudFront's standard log delivery cannot write
      # to a bucket encrypted with a customer managed key, and silently delivers nothing.
      sse_algorithm = "AES256"
    }
  }
}

# CloudFront's standard logging writes with an ACL, and S3 buckets created today have ACLs
# disabled (`BucketOwnerEnforced`). Without this the distribution accepts the configuration
# and then delivers no logs at all — a failure with no error anywhere.
resource "aws_s3_bucket_ownership_controls" "frontend_logs" {
  bucket = aws_s3_bucket.frontend_logs.id

  rule {
    object_ownership = "BucketOwnerPreferred"
  }
}

resource "aws_s3_bucket_acl" "frontend_logs" {
  depends_on = [aws_s3_bucket_ownership_controls.frontend_logs]
  bucket     = aws_s3_bucket.frontend_logs.id

  access_control_policy {
    # The well-known canonical user for `awslogsdelivery`, the account CloudFront delivers
    # standard logs from. It is the same in every commercial region and is documented by AWS;
    # it is not an identifier of this account.
    grant {
      grantee {
        type = "CanonicalUser"
        id   = "c4c1ede66af53448b93c283ce9448c4ba468c9432aa01d700d3878632f77d2d0"
      }
      permission = "FULL_CONTROL"
    }

    grant {
      grantee {
        type = "CanonicalUser"
        id   = data.aws_canonical_user_id.current.id
      }
      permission = "FULL_CONTROL"
    }

    owner {
      id = data.aws_canonical_user_id.current.id
    }
  }
}

# Bounded, because the alternative is keeping a record of who visited for ever. The Política
# states a retention period for operational records and this is the number behind it.
resource "aws_s3_bucket_lifecycle_configuration" "frontend_logs" {
  bucket = aws_s3_bucket.frontend_logs.id

  rule {
    id     = "expire"
    status = "Enabled"

    filter {}

    expiration {
      days = var.frontend_log_retention_days
    }
  }
}
