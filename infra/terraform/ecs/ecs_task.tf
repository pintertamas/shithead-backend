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
      #healthCheck = {
      #  command = ["CMD-SHELL", "curl -f http://localhost/health || exit 1"]
      #  interval    = 30
      #  timeout     = 5
      #  retries     = 3
      #  startPeriod = 10
      #}
      environment = [
        { name = "GAME_SESSION_ID", value = "" }, # override in Lambda
        { name = "USER_ID", value = "" }, # override in Lambda
        { name = "IDLE_TIMEOUT_MINUTES", value = tostring(var.idle_timeout_minutes) },
        { name = "AWS_REGION", value = var.aws_region },
        { name = "GAME_SESSIONS_TABLE", value = var.game_sessions_table },
        { name = "USERS_TABLE", value = var.users_table }
      ]
    }
  ])
}
