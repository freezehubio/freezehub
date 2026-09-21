locals {
  name = "freezehub-${var.environment}"

  # The SPA's Content-Security-Policy has to allow calls to the API, and the API's address
  # is the same on either posture — Caddy on the box (D-35) or the load balancer — because
  # both are reached at this name. So the CSP does not change when the compute does, which
  # is one more thing FZ-157's migration does not have to touch.
  api_domain = var.api_domain_name

  # Where the Hosted UI lives. Derived rather than configured: the pool domain and the
  # region are both already known here, and a third variable that can disagree with them
  # is a third thing to get wrong (FZ-179).
  cognito_domain = "${aws_cognito_user_pool_domain.main.domain}.auth.${var.region}.amazoncognito.com"
}

data "aws_caller_identity" "current" {}
