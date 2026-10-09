output "go_api_base_url" {
  description = "Base URL of the Go REST API including the stage (no trailing slash)"
  value       = aws_api_gateway_stage.go_api.invoke_url
}

output "go_websocket_url" {
  description = "WebSocket URL of the Go backend (wss://, stage $default)"
  value       = aws_apigatewayv2_stage.go_ws.invoke_url
}

output "go_lambda_function_name" {
  description = "Name of the Go Lambda function"
  value       = aws_lambda_function.go_api.function_name
}
