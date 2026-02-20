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

variable "users_table" {
  description = "Name of the DynamoDB table for users"
  type        = string
}

variable "game_sessions_table" {
  description = "Name of the DynamoDB table for game sessions"
  type        = string
}

variable "aws_region" {
  description = "AWS region where the resources will be created"
  type        = string
}

variable "idle_timeout_minutes" {
  description = "Idle timeout in minutes for game sessions"
  type        = number
  default     = 15
}

variable "cloudwatch_logs" {
  description = "CloudWatch Logs configuration for ECS tasks"
  type        = string
}