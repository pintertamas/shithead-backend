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

variable "aws_region" {
  description = "AWS region where the resources will be created"
  type        = string
}

variable "cognito_domain" {
  description = "Cognito user pool domain"
  type        = string
}

variable "cognito_client_id" {
  description = "Cognito user pool client ID"
  type        = string
}

variable "callback_url" {
  description = "Callback URL for Cognito authentication"
  type        = string
  default     = "https://shithead.tamaspinter.com/auth/callback"
}

variable "idle_timeout_minutes" {
  description = "Idle timeout in minutes for game sessions"
  type        = number
  default     = 15
}