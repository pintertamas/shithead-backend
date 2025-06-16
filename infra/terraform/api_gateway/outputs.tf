### Outputs for API Gateway resources
output "api_gateway_game_api_id" {
  description = "The ID of the Game API Gateway"
  value       = aws_api_gateway_rest_api.game_api.id
}

output "api_gateway_game_api_url" {
  description = "The endpoint URL of the Game API Gateway"
  value       = aws_api_gateway_deployment.deployment.invoke_url
}

### Outputs for WebSocket resources
output "websocket_endpoint" {
  description = "WebSocket endpoint (wss://...)"
  value       = aws_apigatewayv2_api.game_ws.api_endpoint
}

output "apigateway_ws_execution_arn" {
  description = "Execution ARN for the WebSocket API Gateway"
  value       = aws_apigatewayv2_api.game_ws.execution_arn
}

output "websocket_api_id" {
  description = "ID of the WebSocket API Gateway"
  value       = aws_apigatewayv2_api.game_ws.id
}