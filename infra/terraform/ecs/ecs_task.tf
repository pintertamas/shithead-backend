resource "aws_ecs_task_definition" "game_task" {
  family = "${var.project_name}-game-session"
  requires_compatibilities = ["FARGATE"]
  network_mode = "awsvpc"            # Fargate requires awsvpc
  cpu    = "256"
  memory = "512"

  execution_role_arn = aws_iam_role.ecs_task_exec.arn
  task_role_arn      = aws_iam_role.ecs_task_role.arn

  container_definitions = jsonencode([
    {
      name      = var.game_container_name
      image     = var.ecr_repository_game_repository_url
      essential = true
      portMappings = [
        { containerPort = 80, hostPort = 80 }
      ]
      environment = [
        { name = "GAME_SESSION_ID", value = "" }, # override in Lambda
        { name = "USER_ID", value = "" }, # override in Lambda
        { name = "COGNITO_DOMAIN", value = var.cognito_domain },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "CLIENT_ID", value = var.cognito_client_id },
        { name = "CALLBACK_URL", value = var.callback_url },
        { name = "IDLE_TIMEOUT_MINUTES", value = tostring(var.idle_timeout_minutes) }
      ]
    }
  ])
}
