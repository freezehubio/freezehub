variable "environment" {
  description = "Environment name, used in every resource name. One workspace per environment."
  type        = string
  default     = "beta"
}

# No default, deliberately (FZ-135, OI-20). This is the one variable that cannot be changed
# after the first apply without moving customer data *and* re-creating every identity: a
# Cognito user pool is region-bound and cannot be migrated, and the `sub` it issues is
# stored in `users.external_subject`. `us-east-1` sat here as a default and was therefore
# never chosen by anybody. It must now be stated, exactly like `domain_name`.
variable "region" {
  description = "AWS region for everything except the CloudFront certificate, which AWS requires from us-east-1. Must be stated: changing it after the first apply is a data migration, not a variable."
  type        = string
}

variable "domain_name" {
  description = "Apex or subdomain the SPA is served from, e.g. app.freezehub.example."
  type        = string
}

variable "api_domain_name" {
  description = "Hostname the Policy API is served from, e.g. api.freezehub.example. Stated rather than derived from domain_name (FZ-174): it is the URL that ends up in every customer's CI configuration."
  type        = string
}

variable "hosted_zone_id" {
  description = "Route 53 hosted zone that already delegates domain_name. Certificate validation and the DNS records both need it."
  type        = string
}

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.micro is the smallest that runs PostgreSQL 16 and is adequate for beta load."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Gigabytes. Storage autoscaling raises it as needed; this is the floor."
  type        = number
  default     = 20
}

variable "backend_image_tag" {
  description = "Image tag in the ECR repository to run. Set by CI (FZ-064); never 'latest', so a rollback is a tag change."
  type        = string
  default     = "bootstrap"
}

variable "backend_desired_count" {
  description = "Number of Fargate tasks. Two so a deployment or an AZ failure does not mean an outage."
  type        = number
  default     = 2
}

variable "backend_cpu" {
  description = "Fargate CPU units. 512 = 0.5 vCPU."
  type        = number
  default     = 512
}

variable "backend_memory" {
  description = "Fargate memory in MiB. The JVM needs headroom above its heap."
  type        = number
  default     = 1024
}


# ——— supplied by ../shared ——————————————————————————————————————————————————————————
#
# Wired by hand rather than by a remote state data source, deliberately: reading another
# module's state couples this one to where that state lives, and these are four values a
# person can paste from `terraform -chdir=../shared output`.

variable "ecr_repository_url" {
  description = "Push target and pull source. `terraform -chdir=../shared output -raw ecr_repository_url`."
  type        = string
}

variable "cognito_user_pool_id" {
  description = "`terraform -chdir=../shared output -raw cognito_user_pool_id`."
  type        = string
}

variable "cognito_user_pool_arn" {
  description = "Scopes the task role to this pool alone. `terraform -chdir=../shared output -raw cognito_user_pool_arn`."
  type        = string
}

variable "github_deploy_role_name" {
  description = "The role ../shared creates. This module attaches its ECS statements to it. `terraform -chdir=../shared output -raw github_deploy_role_name`."
  type        = string
}

variable "aws_account_id" {
  description = "The AWS account this module may be applied into. Every provider asserts it, so an apply with the wrong credentials fails before creating anything (FZ-175). No default: the point is that it cannot be inherited."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "An AWS account id is twelve digits."
  }
}
