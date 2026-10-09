variable "project_name" {
  description = "Base name for all resources"
  type        = string
}

variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
}

variable "cognito_user_pool_id" {
  description = "Cognito user pool id (used for the JWT issuer)"
  type        = string
}

variable "cognito_user_pool_arn" {
  description = "Cognito user pool ARN (REST API authorizer provider)"
  type        = string
}

variable "cognito_user_pool_client_id" {
  description = "Cognito app client id (JWT audience)"
  type        = string
}

variable "games_table_name" {
  description = "Name of the game sessions table"
  type        = string
}

variable "games_table_arn" {
  description = "ARN of the game sessions table"
  type        = string
}

variable "users_table_name" {
  description = "Name of the users table"
  type        = string
}

variable "users_table_arn" {
  description = "ARN of the users table"
  type        = string
}

variable "ws_connections_table_name" {
  description = "Name of the WebSocket connection registry table"
  type        = string
}

variable "ws_connections_table_arn" {
  description = "ARN of the WebSocket connection registry table"
  type        = string
}

variable "artifact_path" {
  description = "Path of the zipped arm64 bootstrap binary built from backend-go"
  type        = string
  default     = "../../../backend-go/build/shithead-api.zip"
}

variable "stage_name" {
  description = "Stage name of the REST API"
  type        = string
  default     = "prod"
}
