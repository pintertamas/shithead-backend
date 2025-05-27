output "post_confirmation_lambda_arn" {
  description = "ARN of the post-confirmation Lambda function"
  value       = aws_lambda_function.post_confirmation.arn
}

output "post_confirmation_function_name" {
  description = "Name of the post-confirmation Lambda function"
  value       = aws_lambda_function.post_confirmation.function_name
}

output "create_game_lambda_arn" {
  description = "Invoke ARN of the create game Lambda function"
  value       = aws_lambda_function.create_game.invoke_arn
}

output "create_game_function_name" {
  description = "Name of the create game Lambda function"
  value       = aws_lambda_function.create_game.function_name
}

