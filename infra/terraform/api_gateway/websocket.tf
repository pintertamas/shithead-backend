resource "aws_apigatewayv2_api" "game_ws" {
  name                       = "game-ws-api"
  protocol_type              = "WEBSOCKET"
  route_selection_expression = "$request.body.action"
}

resource "aws_apigatewayv2_integration" "connect" {
  api_id                 = aws_apigatewayv2_api.game_ws.id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.aws_lambda_function_ws_connect_arn}/invocations"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_integration" "disconnect" {
  api_id                 = aws_apigatewayv2_api.game_ws.id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.aws_lambda_function_ws_disconnect_arn}/invocations"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_integration" "default" {
  api_id                 = aws_apigatewayv2_api.game_ws.id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.aws_lambda_function_ws_default_arn}/invocations"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_route" "connect" {
  api_id             = aws_apigatewayv2_api.game_ws.id
  route_key          = "$connect"
  target             = "integrations/${aws_apigatewayv2_integration.connect.id}"
  authorization_type = "CUSTOM"
  authorizer_id      = aws_apigatewayv2_authorizer.ws.id
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

resource "aws_apigatewayv2_integration" "play_card" {
  api_id                 = aws_apigatewayv2_api.game_ws.id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.play_card_ws_invoke_arn}/invocations"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_route" "play_card" {
  api_id    = aws_apigatewayv2_api.game_ws.id
  route_key = "play"
  target    = "integrations/${aws_apigatewayv2_integration.play_card.id}"
}

resource "aws_apigatewayv2_integration" "pickup_pile" {
  api_id                 = aws_apigatewayv2_api.game_ws.id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.pickup_pile_ws_invoke_arn}/invocations"
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_route" "pickup_pile" {
  api_id    = aws_apigatewayv2_api.game_ws.id
  route_key = "pickup"
  target    = "integrations/${aws_apigatewayv2_integration.pickup_pile.id}"
}

resource "aws_apigatewayv2_stage" "default_stage" {
  api_id = aws_apigatewayv2_api.game_ws.id
  name   = "$default"

  default_route_settings {
    logging_level      = "INFO"
    data_trace_enabled = false
  }

  access_log_settings {
    destination_arn = "${var.aws_cloudwatch_log_group_websocket_apigw_arn}:*"
    format          = "{ \"requestId\":\"$context.requestId\", \"extendedRequestId\":\"$context.extendedRequestId\", \"ip\":\"$context.identity.sourceIp\", \"caller\":\"$context.identity.caller\", \"user\":\"$context.identity.user\", \"requestTime\":\"$context.requestTime\", \"httpMethod\":\"$context.httpMethod\", \"resourcePath\":\"$context.resourcePath\", \"status\":\"$context.status\", \"protocol\":\"$context.protocol\", \"responseLength\":\"$context.responseLength\" }"
  }

  auto_deploy = true
}
