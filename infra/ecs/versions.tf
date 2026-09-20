# The ECS compute estate (FZ-159).
#
# ALB, Fargate, RDS and the network they sit in. Applied only on the ECS posture; the
# single box (D-35) never creates any of it. What both postures share — Cognito, the SPA,
# the registry, the deploy role — lives in ../shared.

terraform {
  required_version = ">= 1.6"

  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.60" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }

  # State holds the database password and the application encryption key, so it must not
  # live on a laptop. Create the bucket and lock table with ../bootstrap first, then fill
  # these in — they cannot be variables, Terraform requires literals here.
  backend "s3" {
    # bucket         = "freezehub-tfstate-<account-id>"
    # key            = "beta/ecs.tfstate"
    # region         = "us-east-1"
    # dynamodb_table = "freezehub-tfstate-lock"
    # encrypt        = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "freezehub"
      Environment = var.environment
      ManagedBy   = "terraform"
      Estate      = "ecs"
    }
  }
}

# The API certificate is regional, but the ALB listener needs it in var.region. Kept for
# parity with ../shared so a file moved between them does not lose its provider.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"
}
