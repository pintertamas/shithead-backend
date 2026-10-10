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


variable "aws_dynamodb_table_ws_connections_arn" {
  description = "ARN of the DynamoDB table for WebSocket connections"
  type        = string
}

variable "aws_dynamodb_table_ws_connection_name" {
  description = "Name of the DynamoDB table for WebSocket connections"
  type        = string
}

variable "aws_apigateway_ws_execution_arn" {
  description = "Execution ARN of the API Gateway for WebSocket connections"
  type        = string
}

variable "websocket_api_endpoint" {
  description = "WebSocket API endpoint used for administrative connection cleanup"
  type        = string
}

variable "cognito_user_pool_id" {
  description = "ID of the Cognito User Pool"
  type        = string
}

variable "cognito_user_pool_arn" {
  description = "ARN of the Cognito User Pool (the game API deletes a user's own account with AdminDeleteUser)"
  type        = string
}

variable "livekit_url" {
  description = "LiveKit server WebSocket URL (empty disables voice chat)"
  type        = string
  default     = ""
}

variable "livekit_api_key" {
  description = "LiveKit API key (empty disables voice chat)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "livekit_api_secret" {
  description = "LiveKit API secret (empty disables voice chat)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "cognito_user_pool_client_id" {
  description = "Client ID of the Cognito User Pool"
  type        = string
}


variable "aws_region" {
  description = "AWS region where resources are deployed"
  type        = string
  default     = "eu-central-1"
}
