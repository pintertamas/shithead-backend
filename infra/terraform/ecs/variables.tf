variable "project_name" {
    description = "Name of the project"
    type        = string
}

variable "ecr_repository_game_repository_url" {
  description = "URL of the ECR repository for the game server"
  type        = string
}

variable "ecs_cluster_name" {
    description = "Name of the ECS cluster"
    type        = string
    default     = "game-cluster"
}

variable "game_container_name" {
  description = "Name of the game container in the ECS task definition"
  type        = string
    default     = "game-container"
}