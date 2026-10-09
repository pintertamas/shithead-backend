### Glue functions (create-game, WebSocket connect/disconnect/default, Cognito init-user).
# The output names are kept so the api_gateway and cognito module wiring stays unchanged.

output "init_user_lambda_arn" {
  description = "ARN of the Cognito post-confirmation/post-authentication trigger"
  value       = aws_lambda_function.glue.arn
}

output "init_user_function_name" {
  description = "Name of the Cognito post-confirmation/post-authentication trigger"
  value       = aws_lambda_function.glue.function_name
}

output "create_game_lambda_arn" {
  description = "ARN of the create-game function"
  value       = aws_lambda_function.glue.arn
}

output "create_game_lambda_invoke_arn" {
  description = "Invoke ARN of the create-game function"
  value       = aws_lambda_function.glue.invoke_arn
}

output "create_game_function_name" {
  description = "Name of the create-game function"
  value       = aws_lambda_function.glue.function_name
}

output "aws_lambda_function_ws_connect_arn" {
  description = "ARN of the WebSocket connect function"
  value       = aws_lambda_function.glue.arn
}

output "aws_lambda_function_ws_connect_function_name" {
  description = "Name of the WebSocket connect function"
  value       = aws_lambda_function.glue.function_name
}

output "aws_lambda_function_ws_disconnect_arn" {
  description = "ARN of the WebSocket disconnect function"
  value       = aws_lambda_function.glue.arn
}

output "aws_lambda_function_ws_disconnect_function_name" {
  description = "Name of the WebSocket disconnect function"
  value       = aws_lambda_function.glue.function_name
}

output "aws_lambda_function_ws_default_arn" {
  description = "ARN of the WebSocket default function"
  value       = aws_lambda_function.glue.arn
}

output "aws_lambda_function_ws_default_function_name" {
  description = "Name of the WebSocket default function"
  value       = aws_lambda_function.glue.function_name
}

### WebSocket REQUEST authorizer

output "ws_lambda_function_ws_authorizer_arn" {
  description = "ARN of the WebSocket authorizer function"
  value       = aws_lambda_function.authorizer.arn
}

output "ws_lambda_function_ws_authorizer_function_name" {
  description = "Name of the WebSocket authorizer function"
  value       = aws_lambda_function.authorizer.function_name
}

### Java game API (one function for every REST and WebSocket gameplay/profile route)

output "game_api_alias_arn" {
  description = "ARN of the LIVE alias of the Java game API function"
  value       = aws_lambda_alias.game_api_live.arn
}

output "game_api_function_name" {
  description = "Name of the Java game API function"
  value       = aws_lambda_function.game_api.function_name
}

output "aws_iam_role_lambda_exec_arn" {
  description = "ARN of the IAM role used by the glue functions and the authorizer"
  value       = aws_iam_role.lambda_exec.arn
}
