output "ecs_cluster_name" {
    description = "Name of the ECS cluster"
    value       = aws_ecs_cluster.game_cluster.name
}

output "ecs_game_task_arn" {
    description = "ARN of the ECS game task"
    value       = aws_ecs_task_definition.game_task.arn
}

output "game_container_name" {
    description = "Name of the game container in the ECS task definition"
    value = jsondecode(aws_ecs_task_definition.game_task.container_definitions)[0].name
}

output "game_task_name" {
    description = "Name of the ECS game task"
    value       = aws_ecs_task_definition.game_task.family
}