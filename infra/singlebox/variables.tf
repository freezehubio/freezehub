variable "environment" {
  description = "Environment name, used in every resource name."
  type        = string
  default     = "beta"
}

variable "region" {
  description = "The region everything runs in. No default: D-32 decided us-east-2, and a region inherited by accident is a migration of every identity to undo (FZ-135)."
  type        = string
}

variable "domain_name" {
  description = "Apex or subdomain the SPA is served from."
  type        = string
}

variable "api_domain_name" {
  description = "Hostname the Policy API is served from, and what Caddy requests a certificate for. Stated rather than derived from domain_name (FZ-174): it is the URL that ends up in every customer's CI configuration."
  type        = string
}

variable "hosted_zone_id" {
  description = "Route 53 hosted zone that already delegates domain_name."
  type        = string
}

variable "instance_type" {
  description = "ARM64, to match the linux/arm64 image CI already builds. t4g.small is 2 GB, which carries the measured 353 MiB working set (D-28) plus PostgreSQL with room. t4g.medium buys a second replica and removes the deploy gap."
  type        = string
  default     = "t4g.small"
}

variable "root_volume_size" {
  description = "Gigabytes. Holds the OS, images, and the PostgreSQL volume."
  type        = number
  default     = 30
}

variable "ecr_repository_arn" {
  description = "ARN of the backend repository in ../shared. The instance may pull from this one and no other."
  type        = string
}

variable "backup_retention_days" {
  description = "How long a nightly dump is kept before lifecycle expiry."
  type        = number
  default     = 30
}
