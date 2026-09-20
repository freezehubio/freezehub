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

variable "github_repository" {
  description = "owner/name of the repository allowed to deploy. The OIDC trust policy is scoped to it and to master, so getting this wrong is the difference between only this repository deploying and anyone's doing so."
  type        = string
  default     = "freezehubio/freezehub"
}
