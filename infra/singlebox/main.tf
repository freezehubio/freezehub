locals {
  name       = "freezehub-${var.environment}"
  api_domain = var.api_domain_name
}

data "aws_caller_identity" "current" {}

# The default VPC, deliberately. D-35: one instance in a public subnet needs no custom
# network, and ../network.tf's 183 lines buy nothing here. The boundary is the security
# group, which is where it would have been in a private subnet too.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Amazon Linux 2023, ARM64, resolved rather than pinned: a pinned AMI is a security update
# nobody applies. The instance is cattle — its state is the EBS volume and SSM parameters.
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-kernel-6.*-arm64"]
  }
}

# ——— address ———————————————————————————————————————————————————————————————————————

# So the address survives a stop/start. A Route 53 record chasing an ephemeral IP is an
# outage that looks like DNS.
resource "aws_eip" "main" {
  domain = "vpc"
  tags   = { Name = local.name }
}

resource "aws_eip_association" "main" {
  instance_id   = aws_instance.main.id
  allocation_id = aws_eip.main.id
}

resource "aws_route53_record" "api" {
  zone_id = var.hosted_zone_id
  name    = local.api_domain
  type    = "A"
  ttl     = 60
  records = [aws_eip.main.public_ip]
}

# ——— network boundary ——————————————————————————————————————————————————————————————

# 80 and 443 inbound and nothing else. No 22: FZ-154 deploys through SSM, so there is no
# port to leave open and no key for anyone to still have a copy of.
#
# 80 is not redundant — Caddy needs it for the ACME HTTP-01 challenge, and it redirects
# everything else to 443.
resource "aws_security_group" "main" {
  name        = local.name
  description = "FreezeHub single-box: public HTTPS in, everything out"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "ACME HTTP-01 challenge and the redirect to HTTPS"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "The product, and the Policy API every customer pipeline calls"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Slack, customer webhooks, SES, Paddle, ECR, SSM. All of it outbound HTTPS.
  egress {
    description = "Outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = local.name }
}
