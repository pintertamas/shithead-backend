resource "aws_cognito_user_pool" "players" {
  name                     = "${var.project_name}-userpool"
  auto_verified_attributes = ["email"]
}

resource "aws_cognito_user_pool_client" "app_client" {
  name                              = "${var.project_name}-client"
  user_pool_id                      = aws_cognito_user_pool.players.id
  allowed_oauth_flows               = ["code"]
  allowed_oauth_scopes              = ["email", "openid", "profile"]
  allowed_oauth_flows_user_pool_client = true

  callback_urls = var.callback_url
}
