terraform {
  required_version = ">= 1.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket = "shithead-game-state-bucket"
    key    = "shithead/terraform.tfstate"
    region = "eu-central-1"
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
  aws_apigateway_ws_execution_arn       = module.api_gateway.apigateway_ws_execution_arn
  aws_dynamodb_table_ws_connection_name = module.dynamodb.aws_dynamodb_table_ws_connections_name
  aws_dynamodb_table_ws_connections_arn = module.dynamodb.aws_dynamodb_table_ws_connections_arn
  cognito_user_pool_client_id           = module.cognito.user_pool_client_id
  cognito_user_pool_id                  = module.cognito.user_pool_id
}

module "dynamodb" {
  source       = "./dynamodb"
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
  aws_lambda_function_ws_authorizer_arn           = module.lambda.ws_lambda_function_ws_authorizer_arn
  aws_lambda_function_ws_authorizer_function_name = module.lambda.ws_lambda_function_ws_authorizer_function_name
  aws_cloudwatch_log_group_websocket_apigw_arn    = module.cloudwatch.aws_cloudwatch_log_group_websocket_apigw_arn
  join_game_invoke_arn                            = module.lambda.join_game_alias_arn
  join_game_function_name                         = module.lambda.join_game_function_name
  start_game_invoke_arn                           = module.lambda.start_game_alias_arn
  start_game_function_name                        = module.lambda.start_game_function_name
  get_state_invoke_arn                            = module.lambda.get_state_alias_arn
  get_state_function_name                         = module.lambda.get_state_function_name
  leaderboard_session_invoke_arn                  = module.lambda.leaderboard_session_alias_arn
  leaderboard_session_function_name               = module.lambda.leaderboard_session_function_name
  leaderboard_top_invoke_arn                      = module.lambda.leaderboard_top_alias_arn
  leaderboard_top_function_name                   = module.lambda.leaderboard_top_function_name
  play_card_ws_invoke_arn                         = module.lambda.play_card_ws_alias_arn
  play_card_ws_function_name                      = module.lambda.play_card_ws_function_name
  pickup_pile_ws_invoke_arn                       = module.lambda.pickup_pile_ws_alias_arn
  pickup_pile_ws_function_name                    = module.lambda.pickup_pile_ws_function_name
}


module "cloudwatch" {
  source       = "./cloudwatch"
  project_name = var.project_name
}
