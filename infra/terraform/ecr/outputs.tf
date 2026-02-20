output "ecr_repository_url" {
    description = "URL of the ECR repository for the game server"
    value       = aws_ecr_repository.game_server.repository_url
}