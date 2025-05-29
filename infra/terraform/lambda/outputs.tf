output "post_registration_lambda_arn" {
  description = "ARN of the post-registration Lambda function"
  value       = aws_lambda_function.post_registration.arn
}

output "post_registration_function_name" {
  description = "Name of the post-registration Lambda function"
  value       = aws_lambda_function.post_registration.function_name
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

