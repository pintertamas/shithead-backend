variable "project_name" {
  description = "Name of the project"
  type        = string
}

variable "aws_dynamodb_table_users_name" {
  description = "Name of the DynamoDB table for users"
  type        = string
}

variable "aws_dynamodb_table_users_arn" {
  description = "ARN of the DynamoDB table for users"
  type        = string
}

variable "aws_dynamodb_table_games_name" {
  description = "Name of the DynamoDB table for games"
  type        = string
}

variable "aws_dynamodb_table_games_arn" {
  description = "ARN of the DynamoDB table for games"
  type        = string
}

variable "ecs_cluster_name" {
  description = "Name of the ECS cluster"
  type        = string
}

variable "ecs_task_arn" {
  description = "ARN of the ECS task definition"
  type        = string
}

variable "subnets" {
  description = "List of subnets for the ECS task"
  type = list(string)
}

variable "game_container_name" {
  description = "Name of the game container in ECS"
  type        = string
}

variable "idle_timeout_minutes" {
  description = "Idle timeout in minutes for game sessions"
  type        = number
  default     = 15
}

variable "vpc_id" {
  description = "VPC ID where the ECS cluster is located"
  type        = string
}