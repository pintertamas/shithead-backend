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

variable "google_client_id" {
  description = "Google Client ID for authentication"
  type        = string
}

variable "google_client_secret" {
  description = "Google Client Secret for authentication"
  type        = string
}

variable "game_container_name" {
    description = "Name of the game container in the ECS task definition"
    type        = string
    default     = "game-container"
}

variable "vpc_id" {
    description = "VPC ID where the ECS tasks will run"
    type        = string
}

variable "subnets" {
  description = "List of subnets for the ECS tasks"
  type        = list(string)
}