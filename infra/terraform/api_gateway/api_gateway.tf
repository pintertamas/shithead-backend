resource "aws_api_gateway_rest_api" "game_api" {
  name        = "game_api"
  description = "API for creating game sessions"
  tags = {
    project = var.project_name
  }
}

resource "aws_api_gateway_resource" "create_game" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_rest_api.game_api.root_resource_id
  path_part   = "create-game"
}

resource "aws_api_gateway_method" "post_create_game" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.create_game.id
  http_method   = "POST"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "lambda_integration" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.create_game.id
  http_method             = aws_api_gateway_method.post_create_game.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.create_game_invoke_arn}/invocations"
}

resource "aws_api_gateway_resource" "join_game" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_rest_api.game_api.root_resource_id
  path_part   = "join-game"
}

resource "aws_api_gateway_method" "post_join_game" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.join_game.id
  http_method   = "POST"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "join_game" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.join_game.id
  http_method             = aws_api_gateway_method.post_join_game.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.join_game_invoke_arn}/invocations"
}

resource "aws_api_gateway_resource" "start_game" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_rest_api.game_api.root_resource_id
  path_part   = "start-game"
}

resource "aws_api_gateway_method" "post_start_game" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.start_game.id
  http_method   = "POST"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "start_game" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.start_game.id
  http_method             = aws_api_gateway_method.post_start_game.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.start_game_invoke_arn}/invocations"
}

resource "aws_api_gateway_resource" "state" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_rest_api.game_api.root_resource_id
  path_part   = "state"
}

resource "aws_api_gateway_resource" "state_session" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.state.id
  path_part   = "{sessionId}"
}

resource "aws_api_gateway_method" "get_state" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.state_session.id
  http_method   = "GET"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "get_state" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.state_session.id
  http_method             = aws_api_gateway_method.get_state.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.get_state_invoke_arn}/invocations"
}

resource "aws_api_gateway_resource" "leaderboard" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_rest_api.game_api.root_resource_id
  path_part   = "leaderboard"
}

resource "aws_api_gateway_resource" "leaderboard_session" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.leaderboard.id
  path_part   = "session"
}

resource "aws_api_gateway_resource" "leaderboard_session_id" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.leaderboard_session.id
  path_part   = "{sessionId}"
}

resource "aws_api_gateway_method" "get_leaderboard_session" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.leaderboard_session_id.id
  http_method   = "GET"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "leaderboard_session" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.leaderboard_session_id.id
  http_method             = aws_api_gateway_method.get_leaderboard_session.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.leaderboard_session_invoke_arn}/invocations"
}

resource "aws_api_gateway_resource" "leaderboard_top" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.leaderboard.id
  path_part   = "top"
}

resource "aws_api_gateway_method" "get_leaderboard_top" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.leaderboard_top.id
  http_method   = "GET"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = var.cognito_authorizer_id
}

resource "aws_api_gateway_integration" "leaderboard_top" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.leaderboard_top.id
  http_method             = aws_api_gateway_method.get_leaderboard_top.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.leaderboard_top_invoke_arn}/invocations"
}

resource "aws_api_gateway_deployment" "deployment" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id

  triggers = {
    redeploy = sha1(join(",", [
      aws_api_gateway_integration.lambda_integration.id,
      aws_api_gateway_integration.join_game.id,
      aws_api_gateway_integration.start_game.id,
      aws_api_gateway_integration.get_state.id,
      aws_api_gateway_integration.leaderboard_session.id,
      aws_api_gateway_integration.leaderboard_top.id,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "prod" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  deployment_id = aws_api_gateway_deployment.deployment.id
  stage_name    = var.stage_name
}

resource "aws_apigatewayv2_authorizer" "ws" {
  api_id          = aws_apigatewayv2_api.game_ws.id
  name            = "WebSocketJwtAuthorizer"
  authorizer_type = "REQUEST"
  authorizer_uri  = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.aws_lambda_function_ws_authorizer_arn}/invocations"
  identity_sources = ["route.request.header.Sec-WebSocket-Protocol"]
}
