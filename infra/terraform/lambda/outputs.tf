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

output "aws_iam_role_lambda_exec_arn" {
  description = "ARN of the IAM role for Lambda execution"
  value       = aws_iam_role.lambda_exec.arn
}

output "join_game_alias_arn" {
  value = aws_lambda_alias.join_game_live.arn
}

output "join_game_function_name" {
  value = aws_lambda_function.join_game.function_name
}

output "start_game_alias_arn" {
  value = aws_lambda_alias.start_game_live.arn
}

output "start_game_function_name" {
  value = aws_lambda_function.start_game.function_name
}

output "get_state_alias_arn" {
  value = aws_lambda_alias.get_state_live.arn
}

output "get_state_function_name" {
  value = aws_lambda_function.get_state.function_name
}

output "play_card_ws_alias_arn" {
  value = aws_lambda_alias.play_card_ws_live.arn
}

output "play_card_ws_function_name" {
  value = aws_lambda_function.play_card_ws.function_name
}

output "ws_lambda_function_ws_authorizer_arn" {
  description = "ARN of the WebSocket authorizer Lambda function"
  value       = aws_lambda_function.ws_authorizer.arn
}

output "ws_lambda_function_ws_authorizer_function_name" {
  description = "Name of the WebSocket authorizer Lambda function"
  value       = aws_lambda_function.ws_authorizer.function_name
}