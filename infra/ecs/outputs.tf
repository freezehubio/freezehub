output "alb_dns_name" {
  description = "Where api.<domain> points on this posture."
  value       = aws_lb.main.dns_name
}

output "ecs_cluster_name" {
  description = "Consumed by the deploy workflow."
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "Consumed by the deploy workflow."
  value       = aws_ecs_service.backend.name
}

output "database_endpoint" {
  description = "Where FZ-157 restores the dump. Not the credentials."
  value       = aws_db_instance.main.endpoint
}

output "database_secret_arn" {
  description = "Where the database credentials live. Not the credentials themselves."
  value       = aws_secretsmanager_secret.database.arn
}
