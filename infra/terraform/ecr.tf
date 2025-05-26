resource "aws_ecr_repository" "game_server" {
  name                 = "${var.project_name}-repository"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name        = "${var.project_name}-repository"
    project     = var.project_name
  }
}

output "ecr_repository_url" {
  value = aws_ecr_repository.game_server.repository_url
}
