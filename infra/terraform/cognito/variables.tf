variable "project_name" {
    description = "Name of the project"
    type        = string
}

variable "aws_region" {
  type    = string
  default = "eu-central-1"
}

variable "user_pool_name" {
  type    = string
  default = "users"
}

variable "user_pool_domain_prefix" {
  type    = string
  default = "shithead-tamaspinter"
}

variable "app_url" {
  type        = string
  description = "The app's base URL (e.g. https://app.example.com)"
  default     = "https://shithead.tamaspinter.com"
}

variable "api_gateway_game_api_id" {
    type        = string
    description = "API Gateway ID for the game API"
}

# OAuth provider credentials
variable "google_client_id" { type = string }
variable "google_client_secret" { type = string }

variable "post_confirmation_lambda_arn" {
  description = "ARN of the post confirmation Lambda function"
  type        = string
}

variable "post_confirmation_function_name" {
    description = "Name of the post confirmation Lambda function"
    type        = string
}