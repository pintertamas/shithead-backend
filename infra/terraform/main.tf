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
  region = var.aws_region
}

module "cognito" {
  source                          = "./cognito"
  project_name                    = var.project_name
  google_client_id                = var.google_client_id
  google_client_secret            = var.google_client_secret
  init_user_function_name = module.lambda.init_user_function_name
  init_user_lambda_arn    = module.lambda.init_user_lambda_arn
  api_gateway_game_api_id         = module.api_gateway.api_gateway_game_api_id
}

module "lambda" {
  source                                = "./lambda"
  project_name                          = var.project_name
  aws_dynamodb_table_users_name         = "${var.project_name}-users"
  aws_dynamodb_table_users_arn          = module.dynamodb.aws_dynamodb_table_users_arn
  aws_dynamodb_table_games_name         = "${var.project_name}-game-sessions"
  aws_dynamodb_table_games_arn          = module.dynamodb.aws_dynamodb_table_games_arn
  ecs_cluster_name                      = module.ecs.ecs_cluster_name
  ecs_task_arn                          = module.ecs.ecs_game_task_arn
  game_container_name                   = module.ecs.game_container_name
  subnets                               = var.subnets
  aws_apigateway_ws_execution_arn       = module.api_gateway.apigateway_ws_execution_arn
  aws_dynamodb_table_ws_connection_name = module.dynamodb.aws_dynamodb_table_ws_connections_name
  aws_dynamodb_table_ws_connections_arn = module.dynamodb.aws_dynamodb_table_ws_connections_arn
}

module "dynamodb" {
  source       = "./dynamodb"
  project_name = var.project_name
}

module "ecr" {
  source       = "./ecr"
  project_name = var.project_name
}

module "api_gateway" {
  source                                          = "./api_gateway"
  project_name                                    = var.project_name
  aws_region                                      = var.aws_region
  cognito_authorizer_id                           = module.cognito.cognito_authorizer_id
  create_game_function_name                       = module.lambda.create_game_function_name
  create_game_invoke_arn                          = module.lambda.create_game_lambda_arn
  aws_lambda_function_ws_connect_arn              = module.lambda.aws_lambda_function_ws_connect_arn
  aws_lambda_function_ws_connect_function_name    = module.lambda.aws_lambda_function_ws_connect_function_name
  aws_lambda_function_ws_disconnect_arn           = module.lambda.aws_lambda_function_ws_disconnect_arn
  aws_lambda_function_ws_disconnect_function_name = module.lambda.aws_lambda_function_ws_disconnect_function_name
  aws_lambda_function_ws_default_arn              = module.lambda.aws_lambda_function_ws_default_arn
  aws_lambda_function_ws_default_function_name    = module.lambda.aws_lambda_function_ws_default_function_name
}

module "ecs" {
  source                             = "./ecs"
  project_name                       = var.project_name
  aws_region                         = var.aws_region
  game_container_name                = "${var.project_name}-${var.game_container_name}"
  game_sessions_table                = module.dynamodb.game_table_name
  users_table                        = module.dynamodb.user_table_name
  ecr_repository_game_repository_url = module.ecr.ecr_repository_url
  cloudwatch_logs                    = module.cloudwatch.cloudwatch_ecs_logs
}

module "cloudwatch" {
  source       = "./cloudwatch"
  project_name = var.project_name
}
