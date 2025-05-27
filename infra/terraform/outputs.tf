output "api_gateway_game_api_id" {
  description = "The ID of the Game API Gateway"
  value = module.api_gateway.api_gateway_game_api_id
}

output "cognito_login_url" {
  description = "Full Cognito Hosted UI login URL with client_id"
  value = module.cognito.cognito_login_url
}

output "user_pool_id" {
  description = "Cognito User Pool ID"
  value       = module.cognito.user_pool_id
}

output "user_pool_arn" {
  description = "Cognito User Pool ARN"
  value       = module.cognito.user_pool_arn
}

output "user_pool_client_id" {
  description = "Cognito User Pool Client ID"
  value       = module.cognito.user_pool_client_id
}

output "user_pool_domain" {
  description = "Hosted UI domain prefix for Cognito"
  value       = module.cognito.user_pool_domain
}

output "cognito_authorizer_id" {
  description = "Cognito Authorizer ID for API Gateway"
  value       = module.cognito.cognito_authorizer_id
}

output "user_table_name" {
  description = "DynamoDB table name for users"
  value       = module.dynamodb.user_table_name
}

output "aws_dynamodb_table_users_arn"{
  description = "ARN of the DynamoDB table for users"
  value       = module.dynamodb.aws_dynamodb_table_users_arn
}

output "post_registration_lambda_arn" {
  description = "ARN of the post-confirmation Lambda function"
  value       = module.lambda.post_registration_lambda_arn
}

output "post_registration_function_name" {
  description = "Name of the post-confirmation Lambda function"
  value       = module.lambda.post_registration_function_name
}

output "create_game_lambda_arn" {
  description = "Invoke ARN of the create game Lambda function"
  value       = module.lambda.create_game_lambda_arn
}

output "create_game_function_name" {
  description = "Name of the create game Lambda function"
  value       = module.lambda.create_game_function_name
}

output "ecs_cluster_name" {
    description = "Name of the ECS cluster"
    value       = module.ecs.ecs_cluster_name
}

output "ecs_game_task_name" {
    description = "Name of the ECS game task"
    value       = module.ecs.game_task_name
}