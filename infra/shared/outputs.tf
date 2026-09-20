# What the compute estates need from this one. Both ../ecs and ../singlebox take these as
# variables rather than reading this module's state: a remote-state data source would
# couple them to where the state lives, and these are a handful of values a person pastes.

output "ecr_repository_url" {
  description = "Push backend images here; both postures pull from it."
  value       = aws_ecr_repository.backend.repository_url
}

output "ecr_repository_arn" {
  description = "Scopes the single box's instance profile to this repository and no other (FZ-152)."
  value       = aws_ecr_repository.backend.arn
}

output "cognito_user_pool_id" {
  description = "The issuer the backend validates JWTs against."
  value       = aws_cognito_user_pool.main.id
}

output "cognito_user_pool_arn" {
  description = "Scopes a task or instance role to this pool alone."
  value       = aws_cognito_user_pool.main.arn
}

output "cognito_user_pool_client_id" {
  description = "The SPA's app client."
  value       = aws_cognito_user_pool_client.frontend.id
}

output "github_deploy_role_arn" {
  description = "Set as AWS_DEPLOY_ROLE_ARN in the repository's variables."
  value       = aws_iam_role.github_deploy.arn
}

output "github_deploy_role_name" {
  description = "../ecs attaches its own statements to this role."
  value       = aws_iam_role.github_deploy.name
}

output "frontend_bucket" {
  description = "Sync the built SPA here."
  value       = aws_s3_bucket.frontend.bucket
}

output "cloudfront_distribution_id" {
  description = "Invalidate this after a frontend deploy."
  value       = aws_cloudfront_distribution.frontend.id
}
