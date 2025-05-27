output "api_gateway_game_api_id" {
  description = "The ID of the Game API Gateway"
  value       = aws_api_gateway_rest_api.game_api.id
}

output "api_gateway_game_api_url" {
  description = "The endpoint URL of the Game API Gateway"
  value       = aws_api_gateway_deployment.deployment.invoke_url
}