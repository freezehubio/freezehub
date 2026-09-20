resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id

  tags = { Name = local.name }
}

resource "aws_db_instance" "main" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "16"

  instance_class        = var.db_instance_class
  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_allocated_storage * 5

  db_name  = "freezehub"
  username = "freezehub"
  password = random_password.database.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.database.id]
  publicly_accessible    = false

  # Encrypted at rest with the AWS-managed key. This is disk-level, and it is *not* what
  # protects integration credentials — see FZ-049 and OI-4: it does nothing against
  # someone holding a database connection, which is why the application encrypts those
  # values itself before they ever reach here.
  storage_encrypted = true

  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "sun:04:00-sun:05:00"

  # Single-AZ for beta. Multi-AZ roughly doubles the instance cost to remove an outage
  # measured in minutes during a failover. Revisit alongside any availability commitment.
  multi_az = false

  auto_minor_version_upgrade = true
  deletion_protection        = true
  skip_final_snapshot        = false
  final_snapshot_identifier  = "${local.name}-final"

  # Liquibase runs on application startup, so the schema is the application's business.
  # Nothing here creates tables.

  lifecycle {
    # Terraform would otherwise show a diff on every plan, because the password is only
    # ever written here and read back from Secrets Manager.
    ignore_changes = [password]
  }
}
