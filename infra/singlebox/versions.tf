# The single-box beta (FZ-152, D-35).
#
# Deliberately a separate root module from ../ rather than a flag inside it. Two estates
# that can each be applied whole is safer than one that can be half-applied, and it makes
# FZ-157 a switch rather than a rewrite: nothing here has to be untangled from the ECS
# design to leave it behind.
#
# What this does NOT create, because ../shared owns it and both postures use it
# unchanged across a migration: the SPA bucket and its CloudFront distribution, the Cognito
# user pool, the ECR repository, the hosted zone and the GitHub OIDC role. Apply ../shared
# first; this module takes what it needs as variables.

terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
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
      Posture     = "singlebox"
    }
  }
}
