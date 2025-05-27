output "project_name" {
  description = "Name of the project"
  value = var.project_name
}

output "cognito_login_url" {
  description = "Full Cognito Hosted UI login URL with client_id"
  value       = module.cognito.cognito_login_url
}

output "token_endpoint" {
  description = "Cognito token endpoint for OAuth2 authentication"
  value       = "https://${module.cognito.user_pool_domain}.auth.${var.aws_region}.amazoncognito.com/oauth2/token"
}

output "api_gateway_create_game_api_url" {
  description = "URL of the Game API Gateway"
  value       = "${module.api_gateway.api_gateway_game_api_url}/create-game"
}

output "user_pool_client_id" {
  description = "Cognito User Pool Client ID"
  value       = module.cognito.user_pool_client_id
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
