output "cloudwatch_ecs_logs" {
    description = "CloudWatch log group for ECS game session logs"
    value       = aws_cloudwatch_log_group.game_logs.name
}