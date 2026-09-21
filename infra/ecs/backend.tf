# The backend on Fargate, behind an HTTPS load balancer (FZ-159 split this from ECR,
# which both postures share and which now lives in ../shared).

# ECS-specific: the awslogs driver writes here. The single box uses Docker's json-file
# driver instead, so this does not belong in ../shared.
resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.name}"
  retention_in_days = 30
}


# --- Certificate and DNS ---------------------------------------------------------------

resource "aws_acm_certificate" "api" {
  domain_name       = local.api_domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "api_certificate_validation" {
  for_each = {
    for option in aws_acm_certificate.api.domain_validation_options :
    option.domain_name => {
      name   = option.resource_record_name
      record = option.resource_record_value
      type   = option.resource_record_type
    }
  }

  zone_id         = var.hosted_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "api" {
  certificate_arn         = aws_acm_certificate.api.arn
  validation_record_fqdns = [for record in aws_route53_record.api_certificate_validation : record.fqdn]
}

resource "aws_route53_record" "api" {
  zone_id = var.hosted_zone_id
  name    = local.api_domain
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}

# --- Load balancer ----------------------------------------------------------------------

resource "aws_lb" "main" {
  name               = local.name
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "backend" {
  name        = local.name
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  health_check {
    # Readiness, not the aggregate health endpoint: readiness reports false while
    # Liquibase is still migrating, which is exactly when a task must not be sent
    # traffic — and exactly when the aggregate endpoint would already say UP (FZ-062).
    path = "/actuator/health/readiness"
    # Unauthenticated, which is what makes it usable from the load balancer (04-api.md).
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # A JVM under Liquibase migration takes a while to answer. Too short and ECS kills
  # tasks mid-migration in a loop that looks like a crash.
  deregistration_delay = 30
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.api.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# --- IAM ---------------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Used by the ECS agent to pull the image and read the secrets it injects — not by the
# application itself.
resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.database.arn, aws_secretsmanager_secret.encryption_key.arn]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "${local.name}-execution-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# Assumed by the application. Scoped to what it actually does: invite users through
# Cognito (FZ-046) and send mail through SES.
resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json
}

data "aws_iam_policy_document" "task" {
  statement {
    sid       = "InviteUsers"
    actions   = ["cognito-idp:AdminCreateUser", "cognito-idp:AdminGetUser"]
    resources = [var.cognito_user_pool_arn]
  }

  statement {
    sid       = "SendNotificationEmail"
    actions   = ["ses:SendRawEmail", "ses:SendEmail"]
    resources = ["*"] # SES authorises by verified identity, which is not a Terraform-known ARN here
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "${local.name}-task"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# --- Service ------------------------------------------------------------------------------

resource "aws_ecs_cluster" "main" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_task_definition" "backend" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    cpu_architecture        = "ARM64" # cheaper per vCPU-hour, and the image is built for it
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([{
    name      = "backend"
    image     = "${var.ecr_repository_url}:${var.backend_image_tag}"
    essential = true

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    environment = [
      # Deliberately NOT the `local` profile. Activating it here would self-sign JWTs and
      # expose the development sign-in endpoint; its absence is what makes both impossible
      # (FZ-035, 06-security.md).
      { name = "SPRING_PROFILES_ACTIVE", value = var.environment },
      { name = "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
      value = "https://cognito-idp.${var.region}.amazonaws.com/${var.cognito_user_pool_id}" },
      { name = "FREEZEHUB_CORS_ALLOWED_ORIGINS", value = "https://${var.domain_name}" },
      { name = "FREEZEHUB_COGNITO_USER_POOL_ID", value = var.cognito_user_pool_id },
      { name = "FREEZEHUB_COGNITO_REGION", value = var.region },
      { name = "FREEZEHUB_COGNITO_CLIENT_ID", value = var.cognito_user_pool_client_id },
      { name = "SPRING_DATASOURCE_URL",
      value = "jdbc:postgresql://${aws_db_instance.main.endpoint}/${aws_db_instance.main.db_name}" },
    ]

    # Resolved by the ECS agent at start-up, so the values are never in this definition.
    secrets = [
      { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_secretsmanager_secret.database.arn}:username::" },
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_secretsmanager_secret.database.arn}:password::" },
      { name = "FREEZEHUB_SECRETS_ENCRYPTION_KEY", valueFrom = aws_secretsmanager_secret.encryption_key.arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.backend.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "backend"
      }
    }
  }])
}

resource "aws_ecs_service" "backend" {
  name            = local.name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.backend_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = false # egress goes through the NAT gateway
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = 8080
  }

  # Liquibase runs on startup, so a task is not ready the moment it starts. Without this
  # the ALB marks it unhealthy and ECS replaces it, forever.
  health_check_grace_period_seconds = 120

  deployment_circuit_breaker {
    enable   = true
    rollback = true # a bad image rolls itself back rather than taking the service down
  }

  # CI registers a new task definition revision on every deploy (FZ-064), so Terraform
  # must stop trying to reset the service to whichever revision it last created —
  # otherwise the next `terraform apply` silently rolls production back to the image
  # named in var.backend_image_tag.
  #
  # The consequence to accept: the running image is no longer described by this file.
  # It is described by the deploy that put it there, which is why image tags are
  # immutable and named after the commit.
  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.https]
}
