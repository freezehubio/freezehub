# Secret material Terraform generates, so nobody has to invent it or paste it anywhere.
#
# Both land in Secrets Manager and are injected into the task definition by ARN, so the
# values never appear in the task definition, in the ECS console, or in a log.
#
# They do appear in Terraform state, which is why the state bucket in versions.tf is not
# optional.

resource "random_password" "database" {
  length = 32
  # RDS rejects several punctuation characters in a master password, and the ones it
  # accepts are not worth the risk of a quoting bug in a connection string.
  special = false
}

resource "aws_secretsmanager_secret" "database" {
  name        = "${local.name}/database"
  description = "PostgreSQL master credentials"

  # Beta: no recovery window, so a destroy-and-recreate during setup is not blocked for a
  # week by a name still held in the deletion queue. Raise this before real customer data.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "database" {
  secret_id = aws_secretsmanager_secret.database.id
  secret_string = jsonencode({
    username = aws_db_instance.main.username
    password = random_password.database.result
    host     = aws_db_instance.main.address
    port     = aws_db_instance.main.port
    dbname   = aws_db_instance.main.db_name
  })
}

# The AES key protecting stored channel credentials and webhook signing secrets (FZ-049).
#
# 32 bytes, Base64, which is exactly what AesGcmSecretProtector validates on startup.
# LOSING THIS MAKES EVERY STORED CREDENTIAL UNREADABLE — key rotation is not implemented,
# so `terraform taint` on this resource would destroy customer data, not refresh a key.
resource "random_bytes" "encryption_key" {
  length = 32

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_secretsmanager_secret" "encryption_key" {
  name        = "${local.name}/encryption-key"
  description = "AES-256 key for integration credentials at rest (FZ-049). Losing it makes them unreadable."

  recovery_window_in_days = 0

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_secretsmanager_secret_version" "encryption_key" {
  secret_id     = aws_secretsmanager_secret.encryption_key.id
  secret_string = random_bytes.encryption_key.base64
}
