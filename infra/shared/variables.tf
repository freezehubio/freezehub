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
  description = "Whether to create the GitHub Actions OIDC provider and deploy role. Off by default because a new account denies iam:*Provider* through an unmodifiable SCP (OI-43); an apply with it on fails there. Turn it on after activating advanced features (D-37) — done for this account on 2026-09-23 (FZ-205)."
  type        = bool
  default     = false
}

variable "github_repository_owner" {
  description = "Owner (organization or user) of the repository allowed to deploy. Cosmetic in the trust policy — github_owner_id is what is actually matched — but it keeps the subject readable in the console."
  type        = string
  default     = "freezehubio"
}

variable "github_repository_name" {
  description = "Name of the repository allowed to deploy. Cosmetic in the trust policy; github_repository_id is what is matched."
  type        = string
  default     = "freezehub"
}

variable "github_owner_id" {
  description = "Numeric id of the owner. Part of GitHub's immutable subject claim (FZ-207), which is what the token actually carries. Unlike the name it cannot be released and re-registered, so it is the half of the subject that carries the security. Re-derive with: gh api repos/OWNER/REPO --jq .owner.id"
  type        = number
  default     = 329392711
}

variable "github_repository_id" {
  description = "Numeric id of the repository. See github_owner_id. Re-derive with: gh api repos/OWNER/REPO --jq .id"
  type        = number
  default     = 1356791848
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
