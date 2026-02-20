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

variable "aws_lambda_function_ws_authorizer_arn" {
  description = "ARN of the WebSocket authorizer Lambda function"
  type        = string
}

variable "aws_lambda_function_ws_authorizer_function_name" {
  description = "Name of the WebSocket authorizer Lambda function"
  type        = string
}

variable "aws_cloudwatch_log_group_websocket_apigw_arn" {
    description = "ARN of the CloudWatch log group for WebSocket API Gateway"
    type        = string
}

variable "join_game_invoke_arn" {
  description = "Alias ARN of the join game Lambda function"
  type        = string
}

variable "join_game_function_name" {
  description = "Name of the join game Lambda function"
  type        = string
}

variable "start_game_invoke_arn" {
  description = "Alias ARN of the start game Lambda function"
  type        = string
}

variable "start_game_function_name" {
  description = "Name of the start game Lambda function"
  type        = string
}

variable "get_state_invoke_arn" {
  description = "Alias ARN of the get state Lambda function"
  type        = string
}

variable "get_state_function_name" {
  description = "Name of the get state Lambda function"
  type        = string
}

variable "play_card_ws_invoke_arn" {
  description = "Alias ARN of the play card WebSocket Lambda function"
  type        = string
}

variable "play_card_ws_function_name" {
  description = "Name of the play card WebSocket Lambda function"
  type        = string
}

variable "pickup_pile_ws_invoke_arn" {
  description = "Alias ARN of the pickup pile WebSocket Lambda function"
  type        = string
}

variable "pickup_pile_ws_function_name" {
  description = "Name of the pickup pile WebSocket Lambda function"
  type        = string
}
