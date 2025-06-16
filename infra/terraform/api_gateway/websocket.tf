resource "aws_apigatewayv2_api" "game_ws" {
  name                       = "game-ws-api"
  protocol_type              = "WEBSOCKET"
  route_selection_expression = "$request.body.action"
}

resource "aws_apigatewayv2_integration" "connect" {
  api_id                = aws_apigatewayv2_api.game_ws.id
  integration_type      = "AWS_PROXY"
  integration_method    = "POST"
  integration_uri       = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.aws_lambda_function_ws_connect_arn}/invocations"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_integration" "disconnect" {
  api_id                = aws_apigatewayv2_api.game_ws.id
  integration_type      = "AWS_PROXY"
  integration_method    = "POST"
  integration_uri       = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.aws_lambda_function_ws_disconnect_arn}/invocations"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_integration" "default" {
  api_id                = aws_apigatewayv2_api.game_ws.id
  integration_type      = "AWS_PROXY"
  integration_method    = "POST"
  integration_uri       = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.aws_lambda_function_ws_default_arn}/invocations"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_route" "connect" {
  api_id    = aws_apigatewayv2_api.game_ws.id
  route_key = "$connect"
  target    = "integrations/${aws_apigatewayv2_integration.connect.id}"
}

resource "aws_apigatewayv2_route" "disconnect" {
  api_id    = aws_apigatewayv2_api.game_ws.id
  route_key = "$disconnect"
  target    = "integrations/${aws_apigatewayv2_integration.disconnect.id}"
}

resource "aws_apigatewayv2_route" "default" {
  api_id    = aws_apigatewayv2_api.game_ws.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.default.id}"
}

resource "aws_apigatewayv2_deployment" "deploy" {
  api_id = aws_apigatewayv2_api.game_ws.id
  triggers = {
    redeploy = sha1(join(",", [
      aws_apigatewayv2_route.connect.id,
      aws_apigatewayv2_route.disconnect.id,
      aws_apigatewayv2_route.default.id,
    ]))
  }
}

resource "aws_apigatewayv2_stage" "default_stage" {
  api_id      = aws_apigatewayv2_api.game_ws.id
  name        = "$default"
  auto_deploy = true
  depends_on  = [aws_apigatewayv2_deployment.deploy]
}