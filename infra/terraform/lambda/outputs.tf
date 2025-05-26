output "post_confirmation_lambda_arn" {
  description = "ARN of the post-confirmation Lambda function"
  value       = aws_lambda_function.post_confirmation.arn
}

output "post_confirmation_function_name" {
  description = "Name of the post-confirmation Lambda function"
  value       = aws_lambda_function.post_confirmation.function_name
}
