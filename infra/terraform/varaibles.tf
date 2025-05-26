########################################################
# AWS Provider Configuration
########################################################
variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "eu-central-1"
}

variable "terraform_state_bucket" {
  description = "S3 bucket for Terraform remote state"
  type        = string
  default     = "shithead-game-state-bucket"
}

variable "terraform_state_key" {
  type        = string
  description = "Key (path) in S3 bucket for TF state file"
  default = "shithead/terraform.tfstate"
}

variable "project_name" {
  description = "Base name for all resources"
  type        = string
  default     = "shithead"
}

########################################################
# Cognito
########################################################
variable "user_pool_name" {
  type    = string
  default = "shithead-users"
}

variable "user_pool_domain_prefix" {
  type    = string
  default = "shithead-tamaspinter"
}

variable "app_url" {
  type    = string
  description = "The app's base URL (e.g. https://app.example.com)"
  default   = "https://shithead.tamaspinter.com"
}

# OAuth provider credentials
variable "google_client_id"      { type = string }
variable "google_client_secret"  { type = string }
