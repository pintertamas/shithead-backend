# Voice chat token: POST /games/{sessionId}/voice-token. Served by the game API Lambda
# (account_management_invoke_arn points at its LIVE alias, as for the other /games routes).
# The aws_lambda_permission "allow_account_management" already covers every REST path of that alias.

# ── /games/{sessionId} ────────────────────────────────────────────────────────

resource "aws_api_gateway_resource" "game_session" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.games.id
  path_part   = "{sessionId}"
}

# ── /games/{sessionId}/voice-token ────────────────────────────────────────────

resource "aws_api_gateway_resource" "voice_token" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.game_session.id
  path_part   = "voice-token"
}

resource "aws_api_gateway_method" "post_voice_token" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.voice_token.id
  http_method   = "POST"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "post_voice_token" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.voice_token.id
  http_method             = aws_api_gateway_method.post_voice_token.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.account_management_invoke_arn}/invocations"
}

# ── CORS preflight (MOCK) ─────────────────────────────────────────────────────

resource "aws_api_gateway_method" "options_voice_token" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.voice_token.id
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options_voice_token" {
  rest_api_id       = aws_api_gateway_rest_api.game_api.id
  resource_id       = aws_api_gateway_resource.voice_token.id
  http_method       = aws_api_gateway_method.options_voice_token.http_method
  type              = "MOCK"
  request_templates = { "application/json" = "{\"statusCode\": 200}" }
}

resource "aws_api_gateway_method_response" "options_voice_token" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.voice_token.id
  http_method = aws_api_gateway_method.options_voice_token.http_method
  status_code = "200"
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = true
    "method.response.header.Access-Control-Allow-Methods" = true
    "method.response.header.Access-Control-Allow-Origin"  = true
  }
}

resource "aws_api_gateway_integration_response" "options_voice_token" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  resource_id = aws_api_gateway_resource.voice_token.id
  http_method = aws_api_gateway_method.options_voice_token.http_method
  status_code = aws_api_gateway_method_response.options_voice_token.status_code
  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'Content-Type,Authorization'"
    "method.response.header.Access-Control-Allow-Methods" = "'POST,OPTIONS'"
    "method.response.header.Access-Control-Allow-Origin"  = "'*'"
  }
}
