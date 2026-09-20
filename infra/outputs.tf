output "application_url" {
  description = "Where a customer signs in."
  value       = "https://${var.domain_name}"
}

output "api_url" {
  description = "Base URL for the API, including the Policy API a pipeline calls."
  value       = "https://${local.api_domain}"
}

output "ecr_repository_arn" {
  description = "Scopes the single-box instance profile to this repository and no other (FZ-152)."
  value       = aws_ecr_repository.backend.arn
}

output "ecr_repository_url" {
  description = "Push backend images here. Consumed by FZ-064."
  value       = aws_ecr_repository.backend.repository_url
}

output "frontend_bucket" {
  description = "Sync the built frontend here, then invalidate the distribution."
  value       = aws_s3_bucket.frontend.id
}

output "cloudfront_distribution_id" {
  description = "Needed to invalidate the cache after a frontend deploy."
  value       = aws_cloudfront_distribution.frontend.id
}

output "cognito_user_pool_id" {
  description = "The pool the backend validates tokens against and FZ-046 will provision users in."
  value       = aws_cognito_user_pool.main.id
}

output "cognito_client_id" {
  description = "Public client id for the frontend sign-in flow."
  value       = aws_cognito_user_pool_client.frontend.id
}

output "cognito_hosted_ui_domain" {
  description = "Hosted UI domain, which replaces the local dev sign-in endpoint (FZ-035)."
  value       = "https://${aws_cognito_user_pool_domain.main.domain}.auth.${var.region}.amazoncognito.com"
}

output "database_secret_arn" {
  description = "Where the database credentials live. Not the credentials themselves."
  value       = aws_secretsmanager_secret.database.arn
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN repository variable in GitHub (FZ-064)."
  value       = aws_iam_role.github_deploy.arn
}

output "ecs_cluster_name" {
  description = "Set as ECS_CLUSTER in GitHub."
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "Set as ECS_SERVICE in GitHub."
  value       = aws_ecs_service.backend.name
}

output "ecs_task_family" {
  description = "Set as ECS_TASK_FAMILY in GitHub."
  value       = aws_ecs_task_definition.backend.family
}
