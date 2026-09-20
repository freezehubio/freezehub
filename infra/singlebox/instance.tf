resource "aws_instance" "main" {
  ami           = data.aws_ami.al2023.id
  instance_type = var.instance_type

  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.main.id]
  associate_public_ip_address = true
  iam_instance_profile        = aws_iam_instance_profile.main.name

  # IMDSv2 only, and one hop.
  #
  # Not a default worth inheriting. OI-23 is an SSRF in outbound webhook delivery, and the
  # blast radius of that class is whatever the instance metadata endpoint will hand out --
  # which is this instance's role credentials. Requiring a session token defeats a plain
  # GET, and a hop limit of 1 means a process inside a container cannot reach it at all,
  # because the bridge network costs a hop.
  #
  # FZ-126 fixed the application half. This is the half that survives the next SSRF nobody
  # has found yet.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }

  root_block_device {
    volume_size           = var.root_volume_size
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  user_data                   = file("${path.module}/user-data.sh")
  user_data_replace_on_change = false # Changing bootstrap must not silently recreate the database.

  monitoring = false # Detailed monitoring is $2.10/month for a box with one process.

  tags = { Name = local.name }

  lifecycle {
    # The AMI moves as Amazon publishes updates. Replacing the instance on every apply
    # would destroy the PostgreSQL volume with it; patching is unattended-upgrades'
    # job, and replacing the box is a deliberate act with a restore either side of it.
    ignore_changes = [ami]
  }
}

# ——— backups ————————————————————————————————————————————————————————————————————————

resource "aws_s3_bucket" "backups" {
  bucket = "${local.name}-backups-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "backups" {
  bucket                  = aws_s3_bucket.backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "backups" {
  bucket = aws_s3_bucket.backups.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    id     = "expire"
    status = "Enabled"

    filter {}

    expiration {
      days = var.backup_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }
}

# ——— secrets ———————————————————————————————————————————————————————————————————————
#
# SSM Parameter Store rather than Secrets Manager: SecureString standard parameters are
# free, and nothing here needs rotation, cross-account access or versioning beyond what
# SSM already gives. That is $0.80/month, which is not the reason -- the reason is that a
# managed rotation nobody has configured is a feature nobody is using.
#
# Both values land in Terraform state. So does ../secrets.tf's equivalent, and
# ../README.md already says to treat access to the state bucket as access to the database.

resource "random_password" "database" {
  length  = 32
  special = false # Goes into a JDBC URL and a shell environment; nothing worth escaping.
}

resource "aws_ssm_parameter" "database_password" {
  name        = "/${local.name}/database-password"
  description = "PostgreSQL password, read by the compose stack at boot"
  type        = "SecureString"
  value       = random_password.database.result
}

# AES-256-GCM key for integration credentials and webhook signing secrets (D-3, FZ-049).
# The application refuses to start without it outside the local profile, deliberately.
resource "random_bytes" "encryption_key" {
  length = 32
}

resource "aws_ssm_parameter" "encryption_key" {
  name        = "/${local.name}/encryption-key"
  description = "Base64 AES-256 key for integration credentials at rest (D-3)"
  type        = "SecureString"
  value       = random_bytes.encryption_key.base64
}
