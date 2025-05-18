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
    key    = "shithead-backend/terraform.tfstate"
    region = "eu-central-1"
  }
}

provider "aws" {
  profile = "shithead-project"
  region  = var.aws_region
}
