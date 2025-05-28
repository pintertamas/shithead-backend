resource "aws_cloudwatch_log_group" "game_logs" {
  name              = "/ecs/${var.project_name}-game-session"
  retention_in_days = 14
}
