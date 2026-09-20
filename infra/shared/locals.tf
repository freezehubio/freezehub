locals {
  name = "freezehub-${var.environment}"

  # The SPA's Content-Security-Policy has to allow calls to the API, and the API's address
  # is the same on either posture — Caddy on the box (D-35) or the load balancer — because
  # both are reached at this name. So the CSP does not change when the compute does, which
  # is one more thing FZ-157's migration does not have to touch.
  api_domain = "api.${var.domain_name}"
}

data "aws_caller_identity" "current" {}
