# The frontend: a private S3 bucket served through CloudFront.
#
# The bucket is not a website endpoint and is not public. CloudFront reaches it with an
# Origin Access Control, so the only way to the files is through the distribution — which
# is also the only thing terminating TLS.

resource "aws_s3_bucket" "frontend" {
  bucket = "${local.name}-frontend-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  versioning_configuration {
    status = "Enabled" # a bad deploy is recoverable without a rebuild
  }
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = local.name
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_iam_policy_document" "frontend_bucket" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.frontend.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    # Scoped to this distribution, so another account's CloudFront cannot serve the bucket.
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.frontend.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = data.aws_iam_policy_document.frontend_bucket.json
}

# CloudFront only accepts a certificate from us-east-1, whatever region the rest runs in.
resource "aws_acm_certificate" "frontend" {
  provider = aws.us_east_1

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "frontend_certificate_validation" {
  for_each = {
    for option in aws_acm_certificate.frontend.domain_validation_options :
    option.domain_name => {
      name   = option.resource_record_name
      record = option.resource_record_value
      type   = option.resource_record_type
    }
  }

  zone_id         = var.hosted_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "frontend" {
  provider = aws.us_east_1

  certificate_arn         = aws_acm_certificate.frontend.arn
  validation_record_fqdns = [for record in aws_route53_record.frontend_certificate_validation : record.fqdn]
}

# The headers every response carries (FZ-129, OI-26).
#
# The one that needed thought is the Content-Security-Policy, and it is written from what
# the application actually loads rather than from a template. The frontend keeps its bearer
# token in sessionStorage — deliberately, and documented in AuthProvider.tsx — so a script
# injection is how that token leaves. This is the control against that, and it is only
# worth having if it is tight enough to refuse the injection.
#
# Derived, directive by directive:
#
#   script-src 'self'      the application loads no third-party script at all. index.html
#                          references one module, which the build emits under /assets. No
#                          analytics, no tag manager, no CDN. That is worth saying in a
#                          header while it is still true.
#   style-src              Source Serif 4 comes from Google Fonts via an @import in
#                          index.css, which fetches a stylesheet from fonts.googleapis.com
#                          and font files from fonts.gstatic.com.
#   style-src-attr         React sets four inline style attributes, and two of them are
#                          load-bearing: the usage bar's width and the checks chart's bar
#                          heights are computed from data and cannot live in a stylesheet.
#                          Under CSP3 an inline style *attribute* is governed by this
#                          directive, so allowing it here keeps <style> elements and
#                          stylesheets strict. Without it the chart ships with every bar
#                          collapsed to zero — the sort of thing a customer finds first.
#   connect-src            the API, which is the only origin the application calls.
#   frame-ancestors 'none' nothing embeds this, and a freeze console in somebody's iframe
#                          is a clickjacking target with real consequences.
#   img-src 'self'         no external image and no data: URI exists today. If one appears
#                          this refuses it loudly, which is the point.
#
# Stripe is reached by navigating the top level away (window.location.assign), not by an
# embedded frame or an XHR, so no directive here governs it.
resource "aws_cloudfront_response_headers_policy" "frontend" {
  name = "${local.name}-frontend"

  security_headers_config {
    content_security_policy {
      override = true
      content_security_policy = join("; ", [
        "default-src 'self'",
        "script-src 'self'",
        "style-src 'self' https://fonts.googleapis.com",
        "style-src-attr 'unsafe-inline'",
        "font-src 'self' https://fonts.gstatic.com",
        "img-src 'self'",
        "connect-src 'self' https://${local.api_domain}",
        "frame-ancestors 'none'",
        "base-uri 'none'",
        "form-action 'self'",
        "object-src 'none'",
      ])
    }

    # A year, and subdomains: api.<domain> is HTTPS too, behind its own certificate.
    # `preload` is deliberately not set — submitting to the preload list is a one-way
    # door that outlives any decision made here.
    strict_transport_security {
      override                   = true
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      preload                    = false
    }

    content_type_options {
      override = true
    }

    frame_options {
      override     = true
      frame_option = "DENY"
    }

    referrer_policy {
      override        = true
      referrer_policy = "strict-origin-when-cross-origin"
    }
  }

  # Permissions-Policy has no first-class field in this resource. Everything the product
  # has no use for is switched off rather than left to the browser's default.
  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      override = true
      value    = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"
    }
  }
}

resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  default_root_object = "index.html"
  aliases             = [var.domain_name]
  price_class         = "PriceClass_100" # North America and Europe; widen when customers are elsewhere

  origin {
    origin_id                = "frontend"
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  default_cache_behavior {
    target_origin_id       = "frontend"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]

    cache_policy_id            = "658327ea-f89d-4fab-a63d-7e88639e58f6" # AWS managed: CachingOptimized
    response_headers_policy_id = aws_cloudfront_response_headers_policy.frontend.id
  }

  # The app is a single-page application: every route below / is served by index.html and
  # resolved client-side. Without this, deep links and refreshes return S3's 403.
  custom_error_response {
    error_code         = 403
    response_code      = 200
    response_page_path = "/index.html"
  }

  custom_error_response {
    error_code         = 404
    response_code      = 200
    response_page_path = "/index.html"
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.frontend.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }
}

resource "aws_route53_record" "frontend" {
  zone_id = var.hosted_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.frontend.domain_name
    zone_id                = aws_cloudfront_distribution.frontend.hosted_zone_id
    evaluate_target_health = false
  }
}
