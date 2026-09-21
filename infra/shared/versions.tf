# The estate both compute postures share (FZ-159).
#
# Cognito, the SPA bucket and its distribution, the image registry, and the GitHub deploy
# role. None of it depends on whether the backend runs on one box (D-35) or on ECS, and
# none of it moves when that changes — which is what makes FZ-157's migration small.
#
# Separate state from ../ecs, deliberately. One state file for both would mean the ECS
# half could not be destroyed without putting the SPA, the user pool and the registry in
# the same plan.

terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.60" }
  }

  # Holds no secrets of its own, unlike ../ecs. Still remote, so two people and CI agree
  # on what exists. Create the bucket and lock table with ../bootstrap first.
  backend "s3" {
    # bucket         = "freezehub-tfstate-<account-id>"
    # key            = "beta/shared.tfstate"
    # region         = "us-east-2"
    # dynamodb_table = "freezehub-tfstate-lock"
    # encrypt        = true
  }
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project     = "freezehub"
      Environment = var.environment
      ManagedBy   = "terraform"
      Estate      = "shared"
    }
  }
}

# CloudFront certificates must live in us-east-1 whatever region the rest runs in. A
# certificate holds no customer data, so this is not a residency question (D-32).
provider "aws" {
  alias               = "us_east_1"
  region              = "us-east-1"
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project     = "freezehub"
      Environment = var.environment
      ManagedBy   = "terraform"
      Estate      = "shared"
    }
  }
}
