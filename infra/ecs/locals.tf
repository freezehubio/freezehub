locals {
  name = "freezehub-${var.environment}"

  api_domain = "api.${var.domain_name}"

  # Two AZs, which is the minimum RDS and an ALB will accept. Not three: a third doubles
  # nothing useful at beta scale and adds a NAT gateway if ever made per-AZ.
  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  # Non-overlapping /24s inside a /16, leaving room to add subnets without renumbering.
  public_subnets  = ["10.0.0.0/24", "10.0.1.0/24"]
  private_subnets = ["10.0.10.0/24", "10.0.11.0/24"]
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}
