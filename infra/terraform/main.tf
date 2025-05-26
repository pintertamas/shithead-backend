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

module "cognito" {
  source = "./cognito"
  project_name = var.project_name
  google_client_id = var.google_client_id
  google_client_secret = var.google_client_secret
  post_confirmation_function_name = module.lambda.post_confirmation_function_name
  post_confirmation_lambda_arn = module.lambda.post_confirmation_lambda_arn
}

module "lambda" {
  source = "./lambda"
  project_name = var.project_name
  aws_dynamodb_table_users_arn = module.dynamodb.aws_dynamodb_table_users_arn
}

module "dynamodb" {
  source       = "./dynamodb"
  project_name = var.project_name
}

module "ecr" {
  source       = "./ecr"
  project_name = var.project_name
}
