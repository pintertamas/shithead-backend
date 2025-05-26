##########################################################################
# User creation and ELO score management outputs
# Outputs for Cognito User Pool, DynamoDB Table, and Lambda Function
##########################################################################
output "user_pool_id" {
  description = "Cognito User Pool ID"
  value       = aws_cognito_user_pool.users.id
}

output "user_pool_arn" {
  description = "Cognito User Pool ARN"
  value       = aws_cognito_user_pool.users.arn
}

output "user_pool_client_id" {
  description = "Cognito User Pool Client ID"
  value       = aws_cognito_user_pool_client.app_client.id
}

output "user_pool_domain" {
  description = "Hosted UI domain prefix for Cognito"
  value       = aws_cognito_user_pool_domain.hosted_ui.domain
}

output "user_table_name" {
  description = "DynamoDB table name for users"
  value       = aws_dynamodb_table.users.name
}

output "post_confirmation_lambda_arn" {
  description = "ARN of the post-confirmation Lambda function"
  value       = aws_lambda_function.post_confirmation.arn
}

output "cognito_login_url" {
  description = "Full Cognito Hosted UI login URL with client_id"
  value = "https://${aws_cognito_user_pool_domain.hosted_ui.domain}.auth.${var.aws_region}.amazoncognito.com/login?response_type=code&client_id=${aws_cognito_user_pool_client.app_client.id}&redirect_uri=${var.app_url}/auth/callback"
}