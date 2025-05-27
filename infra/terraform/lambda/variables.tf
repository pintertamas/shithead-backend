variable "project_name" {
  description = "Name of the project"
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