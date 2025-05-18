variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "eu-central-1"
}

variable "state_bucket" {
  description = "(Optional) Name of the S3 bucket for remote state"
  type        = string
  default     = "shithead-game-state-bucket"
}

variable "project_name" {
  description = "Base name for all resources"
  type        = string
  default     = "shithead"
}

variable "callback_url" {
  description = "List of OAuth callback URLs for the Cognito App Client"
  type        = list(string)
  default     = [
    "https://tamaspinter.com/oauth2/idpresponse",
    "http://localhost:3000/oauth2/idpresponse",
    "com.tamaspinter.shithead://oauth2redirect",
    "https://oauth.pstmn.io/v1/callback",
    "https://oauth.pstmn.io/v1/browser-callback"
  ]
}
