variable "project_name" {
    description = "Name of the project"
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