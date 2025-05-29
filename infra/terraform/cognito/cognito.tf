resource "aws_cognito_user_pool" "users" {
  name = "${var.project_name}-user-pool"

  # Allow users to sign in with email/password
  username_attributes = ["email"]
  auto_verified_attributes = ["email"]

  lambda_config {
    post_confirmation = var.post_registration_lambda_arn
  }

  password_policy {
    minimum_length                   = 8
    require_uppercase                = true
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = false
    temporary_password_validity_days = 7
  }

  mfa_configuration = "OFF" # Disable MFA for simplicity, can be enabled later TODO
}

resource "aws_api_gateway_authorizer" "cognito_auth" {
  name          = "CognitoAuthorizer"
  rest_api_id   = var.api_gateway_game_api_id
  identity_source = "method.request.header.Authorization"
  type          = "COGNITO_USER_POOLS"
  provider_arns = [aws_cognito_user_pool.users.arn]
}

# Google
resource "aws_cognito_identity_provider" "google" {
  user_pool_id = aws_cognito_user_pool.users.id
  provider_name = "Google"
  provider_type = "Google"

  provider_details = {
    client_id     = var.google_client_id
    client_secret = var.google_client_secret
    authorize_scopes = "openid profile email"
  }

  attribute_mapping = {
    email    = "email"
  }
}

resource "aws_cognito_user_pool_client" "app_client" {
  name         = "${var.project_name}-app-client"
  user_pool_id = aws_cognito_user_pool.users.id

  # Public SPA (no client secret)
  generate_secret = false

  # Allowed login flows
  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH"
  ]

  # OAuth2 code grant
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows  = ["code"]
  allowed_oauth_scopes = ["openid","email","profile"]

  # All the IdPs we set up
  supported_identity_providers = [
    "COGNITO",
    aws_cognito_identity_provider.google.provider_name,
  ]

  # Where Cognito redirects after login/logout
  callback_urls = [
    "${var.app_url}/auth/callback"
  ]
  logout_urls = [
    "${var.app_url}/"
  ]

  # Token lifetimes
  access_token_validity  = 1  # hours
  id_token_validity      = 1  # hours
  refresh_token_validity = 30 # days
}

# Hosted UI Domain
resource "aws_cognito_user_pool_domain" "hosted_ui" {
  domain       = var.project_name
  user_pool_id = aws_cognito_user_pool.users.id
}
