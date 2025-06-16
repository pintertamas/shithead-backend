variable "project_name" {
  description = "Name of the project"
  type        = string
}

variable "aws_region" {
    description = "AWS region to deploy into"
    type        = string
}

variable "create_game_invoke_arn" {
  description = "Invoke ARN of the create game Lambda function"
  type        = string
}

variable "create_game_function_name" {
  description = "Name of the create game Lambda function"
  type        = string
}

variable "cognito_authorizer_id" {
  description = "ID of the Cognito Authorizer for API Gateway"
  type        = string
}

variable "stage_name" {
  description = "Stage name for the API Gateway"
  type        = string
  default     = "prod"
}

variable "aws_lambda_function_ws_connect_function_name" {
    description = "Name of the WebSocket connect Lambda function"
    type        = string
}

variable "aws_lambda_function_ws_disconnect_function_name" {
    description = "Name of the WebSocket disconnect Lambda function"
    type        = string
}

variable "aws_lambda_function_ws_default_function_name" {
    description = "Name of the WebSocket default Lambda function"
    type        = string
}

variable "aws_lambda_function_ws_connect_arn" {
    description = "ARN of the WebSocket connect Lambda function"
    type        = string
}

variable "aws_lambda_function_ws_disconnect_arn" {
  description = "ARN of the WebSocket disconnect Lambda function"
  type        = string
}

variable "aws_lambda_function_ws_default_arn" {
  description = "ARN of the WebSocket default Lambda function"
  type        = string
}