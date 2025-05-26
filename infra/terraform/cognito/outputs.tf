output "cognito_login_url" {
  description = "Full Cognito Hosted UI login URL with client_id"
  value = "https://${aws_cognito_user_pool_domain.hosted_ui.domain}.auth.${var.aws_region}.amazoncognito.com/login?response_type=code&client_id=${aws_cognito_user_pool_client.app_client.id}&redirect_uri=${var.app_url}/auth/callback"
}

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