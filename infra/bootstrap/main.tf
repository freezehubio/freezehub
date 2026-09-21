# Applied once, before anything else, with local state.
#
# The chicken-and-egg problem: the main configuration keeps its state in S3, and that
# bucket has to exist first. This creates it and nothing else, so its own local state
# holds no secrets and losing it costs nothing — the bucket can simply be imported.

terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.60" }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "freezehub"
      ManagedBy = "terraform"
      Purpose   = "tfstate"
    }
  }
}

# us-east-2, not us-east-1. The project's SCP permits only a narrow set of global actions
# in us-east-1 and s3:CreateBucket is not among them, so this bucket cannot be made there
# (D-32, FZ-171). A default is kept here, unlike the other modules, because bootstrap runs
# once before anything exists and a wrong region fails immediately rather than silently.
variable "region" {
  type    = string
  default = "us-east-2"
}

data "aws_caller_identity" "current" {}

locals {
  bucket = "freezehub-tfstate-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "state" {
  bucket = local.bucket

  # State contains the database password and the application encryption key. Deleting it
  # by accident is not a recoverable mistake.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  # The only way back from a corrupted or truncated state file.
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Stops two applies running at once and interleaving writes into one state file.
resource "aws_dynamodb_table" "lock" {
  name         = "freezehub-tfstate-lock"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }
}

output "state_bucket" {
  description = "Put this in the backend block in ../versions.tf."
  value       = aws_s3_bucket.state.id
}

output "lock_table" {
  value = aws_dynamodb_table.lock.name
}
