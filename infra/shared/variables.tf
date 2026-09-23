variable "environment" {
  description = "Environment name, used in every resource name. One workspace per environment."
  type        = string
  default     = "beta"
}

variable "region" {
  description = "The region the user pool and the registry live in. No default (FZ-135, D-32): a Cognito pool is region-bound and users.external_subject stores the sub it issues, so a region inherited by accident is a migration of every identity to undo."
  type        = string
}

variable "domain_name" {
  description = "Apex or subdomain the SPA is served from."
  type        = string
}

variable "hosted_zone_id" {
  description = "Route 53 hosted zone that already delegates domain_name."
  type        = string
}

variable "api_domain_name" {
  description = "Hostname the Policy API is served from. Stated rather than derived from domain_name (FZ-174): it is the URL that ends up in every customer's CI configuration through freeze-check.sh, so it should not inherit whatever subdomain the SPA happens to use. No default, for the same reason domain_name has none — it is a certificate subject name."
  type        = string
}

variable "github_oidc_enabled" {
  description = "Whether to create the GitHub Actions OIDC provider and deploy role. Off by default because this account denies iam:*Provider* through an unmodifiable SCP (OI-43); an apply with it on fails. Turn it on after activating advanced features (D-37)."
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "owner/name of the repository allowed to deploy. The OIDC trust policy is scoped to it and to master, so getting this wrong is the difference between only this repository deploying and anyone's doing so."
  type        = string
  default     = "freezehubio/freezehub"
}

variable "aws_account_id" {
  description = "The AWS account this module may be applied into. Every provider asserts it, so an apply with the wrong credentials fails before creating anything (FZ-175). No default: the point is that it cannot be inherited."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "budget_limit_usd" {
  description = "Monthly cost budget in USD (FZ-204). `D-35` projects the single box at about $18 a month, so the default leaves room for a second environment or a bad day without alerting on an ordinary one. It is an alert, not a cap: nothing stops at this number."
  type        = string
  default     = "40"
}

variable "budget_alert_email" {
  description = "Where budget alerts go (FZ-204). No default, deliberately: an alert nobody receives is worse than no alert, because it looks like a control. This is the replacement for the enforced spend limit that activating advanced features removes (D-37)."
  type        = string
}
