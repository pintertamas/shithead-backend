output "init_user_lambda_arn" {
  description = "ARN of the post-registration Lambda function"
  value       = aws_lambda_function.init_user_function.arn
}

output "init_user_function_name" {
  description = "Name of the post-registration Lambda function"
  value       = aws_lambda_function.init_user_function.function_name
}

output "create_game_lambda_arn" {
  description = "ARN of the create game Lambda function"
  value       = aws_lambda_function.create_game.arn
}

output "create_game_lambda_invoke_arn" {
  description = "Invoke ARN of the create game Lambda function"
  value       = aws_lambda_function.create_game.invoke_arn
}

output "create_game_function_name" {
  description = "Name of the create game Lambda function"
  value       = aws_lambda_function.create_game.function_name
}

output "aws_lambda_function_ws_connect_arn" {
  description = "ARN of the WebSocket connect Lambda function"
  value       = aws_lambda_function.ws_connect.arn
}

output "aws_lambda_function_ws_connect_function_name" {
  description = "Name of the WebSocket connect Lambda function"
  value       = aws_lambda_function.ws_connect.function_name
}

output "aws_lambda_function_ws_disconnect_arn" {
  description = "ARN of the WebSocket disconnect Lambda function"
  value       = aws_lambda_function.ws_disconnect.arn
}

output "aws_lambda_function_ws_disconnect_function_name" {
  description = "Name of the WebSocket disconnect Lambda function"
  value       = aws_lambda_function.ws_disconnect.function_name
}

output "aws_lambda_function_ws_default_arn" {
  description = "ARN of the WebSocket default Lambda function"
  value       = aws_lambda_function.ws_default.arn
}

output "aws_lambda_function_ws_default_function_name" {
  description = "Name of the WebSocket default Lambda function"
  value       = aws_lambda_function.ws_default.function_name
}
