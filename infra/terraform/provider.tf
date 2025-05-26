terraform {
  required_version = ">= 1.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 4.0"
    }
  }

  backend "s3" {
    bucket = "shithead-game-state-bucket"
    key    = "shithead/terraform.tfstate"
    region = "eu-central-1"
    tags = { project = var.project_name }
  }
}

provider "aws" {
  region  = var.aws_region
}
