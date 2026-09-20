output "public_ip" {
  description = "The Elastic IP. Stable across stop/start, which is why the A record can point at it."
  value       = aws_eip.main.public_ip
}

output "instance_id" {
  description = "Target for `aws ssm send-command` (FZ-154) and `aws ssm start-session` (FZ-156)."
  value       = aws_instance.main.id
}

output "api_domain" {
  description = "What Caddy requests a certificate for, and what customer pipelines call."
  value       = local.api_domain
}

output "backup_bucket" {
  description = "Nightly dumps land here. The instance may write and not read (FZ-155)."
  value       = aws_s3_bucket.backups.bucket
}

output "session_command" {
  description = "How to get a shell. There is no SSH."
  value       = "aws ssm start-session --target ${aws_instance.main.id} --region ${var.region}"
}
