# The image registry, shared by both compute postures (FZ-159).
#
# Extracted from backend.tf, which also held the ALB and the ECS service. Both the single
# box (D-35) and the ECS estate pull the same image built by the same CI, so the repository
# cannot belong to either one of them — which is the whole reason this module exists.

resource "aws_ecr_repository" "backend" {
  name                 = local.name
  image_tag_mutability = "IMMUTABLE" # a tag always means the same image, so a rollback is honest

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 20 images; older ones are not rollback targets."
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 20 }
      action       = { type = "expire" }
    }]
  })
}
