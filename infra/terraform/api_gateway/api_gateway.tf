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
  uri                     = var.create_game_invoke_arn
}
