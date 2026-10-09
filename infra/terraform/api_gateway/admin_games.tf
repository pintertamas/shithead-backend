# Admin user list/blocking and the lobby browser. All routes are served by the
# account management Lambda; the admin routes also require the game-admin group in code.

# ── /admin/users ──────────────────────────────────────────────────────────────

resource "aws_api_gateway_resource" "admin_users" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.admin.id
  path_part   = "users"
}

resource "aws_api_gateway_method" "get_admin_users" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.admin_users.id
  http_method   = "GET"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "get_admin_users" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.admin_users.id
  http_method             = aws_api_gateway_method.get_admin_users.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.account_management_invoke_arn}/invocations"
}

# ── /admin/users/{userId} ─────────────────────────────────────────────────────

resource "aws_api_gateway_resource" "admin_user_id" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.admin_users.id
  path_part   = "{userId}"
}

resource "aws_api_gateway_resource" "admin_user_block" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.admin_user_id.id
  path_part   = "block"
}

resource "aws_api_gateway_method" "post_admin_user_block" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.admin_user_block.id
  http_method   = "POST"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "post_admin_user_block" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.admin_user_block.id
  http_method             = aws_api_gateway_method.post_admin_user_block.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.account_management_invoke_arn}/invocations"
}

resource "aws_api_gateway_resource" "admin_user_unblock" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.admin_user_id.id
  path_part   = "unblock"
}

resource "aws_api_gateway_method" "post_admin_user_unblock" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.admin_user_unblock.id
  http_method   = "POST"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "post_admin_user_unblock" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.admin_user_unblock.id
  http_method             = aws_api_gateway_method.post_admin_user_unblock.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.account_management_invoke_arn}/invocations"
}

# ── /games (lobby browser) ────────────────────────────────────────────────────

resource "aws_api_gateway_resource" "games" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_rest_api.game_api.root_resource_id
  path_part   = "games"
}

resource "aws_api_gateway_method" "get_games" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.games.id
  http_method   = "GET"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "get_games" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.games.id
  http_method             = aws_api_gateway_method.get_games.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.account_management_invoke_arn}/invocations"
}

# ── CORS preflight (MOCK) ─────────────────────────────────────────────────────

resource "aws_api_gateway_method" "options_admin_users" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.admin_users.id
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options_admin_users" {
  rest_api_id       = aws_api_gateway_rest_api.game_api.id
  resource_id       = aws_api_gateway_resource.admin_users.id
  http_method       = aws_api_gateway_method.options_admin_users.http_method
  type              = "MOCK"
  request_templates = { "application/json" = "{\"statusCode\": 200}" }
}

resource "aws_api_gateway_method_response" "options_admin_users" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.admin_users.id
  http_method = aws_api_gateway_method.options_admin_users.http_method
  status_code = "200"
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = true
    "method.response.header.Access-Control-Allow-Methods" = true
    "method.response.header.Access-Control-Allow-Origin"  = true
  }
}

resource "aws_api_gateway_integration_response" "options_admin_users" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.admin_users.id
  http_method = aws_api_gateway_method.options_admin_users.http_method
  status_code = aws_api_gateway_method_response.options_admin_users.status_code
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'Content-Type,Authorization'"
    "method.response.header.Access-Control-Allow-Methods" = "'GET,OPTIONS'"
    "method.response.header.Access-Control-Allow-Origin"  = "'*'"
  }
}

resource "aws_api_gateway_method" "options_admin_user_block" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.admin_user_block.id
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options_admin_user_block" {
  rest_api_id       = aws_api_gateway_rest_api.game_api.id
  resource_id       = aws_api_gateway_resource.admin_user_block.id
  http_method       = aws_api_gateway_method.options_admin_user_block.http_method
  type              = "MOCK"
  request_templates = { "application/json" = "{\"statusCode\": 200}" }
}

resource "aws_api_gateway_method_response" "options_admin_user_block" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.admin_user_block.id
  http_method = aws_api_gateway_method.options_admin_user_block.http_method
  status_code = "200"
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = true
    "method.response.header.Access-Control-Allow-Methods" = true
    "method.response.header.Access-Control-Allow-Origin"  = true
  }
}

resource "aws_api_gateway_integration_response" "options_admin_user_block" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.admin_user_block.id
  http_method = aws_api_gateway_method.options_admin_user_block.http_method
  status_code = aws_api_gateway_method_response.options_admin_user_block.status_code
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'Content-Type,Authorization'"
    "method.response.header.Access-Control-Allow-Methods" = "'POST,OPTIONS'"
    "method.response.header.Access-Control-Allow-Origin"  = "'*'"
  }
}

resource "aws_api_gateway_method" "options_admin_user_unblock" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.admin_user_unblock.id
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options_admin_user_unblock" {
  rest_api_id       = aws_api_gateway_rest_api.game_api.id
  resource_id       = aws_api_gateway_resource.admin_user_unblock.id
  http_method       = aws_api_gateway_method.options_admin_user_unblock.http_method
  type              = "MOCK"
  request_templates = { "application/json" = "{\"statusCode\": 200}" }
}

resource "aws_api_gateway_method_response" "options_admin_user_unblock" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.admin_user_unblock.id
  http_method = aws_api_gateway_method.options_admin_user_unblock.http_method
  status_code = "200"
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = true
    "method.response.header.Access-Control-Allow-Methods" = true
    "method.response.header.Access-Control-Allow-Origin"  = true
  }
}

resource "aws_api_gateway_integration_response" "options_admin_user_unblock" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.admin_user_unblock.id
  http_method = aws_api_gateway_method.options_admin_user_unblock.http_method
  status_code = aws_api_gateway_method_response.options_admin_user_unblock.status_code
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'Content-Type,Authorization'"
    "method.response.header.Access-Control-Allow-Methods" = "'POST,OPTIONS'"
    "method.response.header.Access-Control-Allow-Origin"  = "'*'"
  }
}

resource "aws_api_gateway_method" "options_games" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.games.id
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options_games" {
  rest_api_id       = aws_api_gateway_rest_api.game_api.id
  resource_id       = aws_api_gateway_resource.games.id
  http_method       = aws_api_gateway_method.options_games.http_method
  type              = "MOCK"
  request_templates = { "application/json" = "{\"statusCode\": 200}" }
}

resource "aws_api_gateway_method_response" "options_games" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.games.id
  http_method = aws_api_gateway_method.options_games.http_method
  status_code = "200"
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = true
    "method.response.header.Access-Control-Allow-Methods" = true
    "method.response.header.Access-Control-Allow-Origin"  = true
  }
}

resource "aws_api_gateway_integration_response" "options_games" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.games.id
  http_method = aws_api_gateway_method.options_games.http_method
  status_code = aws_api_gateway_method_response.options_games.status_code
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'Content-Type,Authorization'"
    "method.response.header.Access-Control-Allow-Methods" = "'GET,OPTIONS'"
    "method.response.header.Access-Control-Allow-Origin"  = "'*'"
  }
}
