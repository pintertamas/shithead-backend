output "project_name" {
  description = "Name of the project"
  value       = var.project_name
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

output "user_pool_id" {
  description = "Cognito User Pool ID"
  value       = module.cognito.user_pool_id
}

output "token_curl_request" {
  description = "Curl command to get a token from Cognito"
  value       = "curl.exe --location 'https://${var.project_name}.auth.eu-central-1.amazoncognito.com/oauth2/token' --header 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'grant_type=authorization_code' --data-urlencode 'client_id=${module.cognito.user_pool_client_id}' --data-urlencode 'redirect_uri=${trimsuffix(var.app_url, "/")}/auth/callback' --data-urlencode 'code=AUTH_CODE'"
}

output "websocket_connection_test" {
  description = "Test command for WebSocket connection"
  value       = "wscat -c wss://${module.api_gateway.websocket_api_id}.execute-api.${var.aws_region}.amazonaws.com/$default?game_session_id=GAME_SESSION_ID"
}

output "create_game_test_url" {
  description = "Test URL for creating a game session"
  value       = "curl --location --request POST '${module.api_gateway.api_gateway_game_api_url}/create-game' --header 'Authorization: AUTH_TOKEN'"
}
