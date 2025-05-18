resource "aws_apigatewayv2_api" "ws_api" {
  name                       = "${var.project_name}-ws"
  protocol_type              = "WEBSOCKET"
  route_selection_expression = "$request.body.action"
}

locals {
  routes = ["connect","disconnect","createGame","joinGame","playCard"]
}

resource "aws_apigatewayv2_route" "routes" {
  for_each    = toset(local.routes)
  api_id      = aws_apigatewayv2_api.ws_api.id
  route_key = each.value == "connect"  ? "$connect" : each.value == "disconnect" ? "$disconnect" : each.value
  target      = "integrations/${aws_apigatewayv2_integration.ws_integration.id}"
}

resource "aws_apigatewayv2_integration" "ws_integration" {
  api_id           = aws_apigatewayv2_api.ws_api.id
  integration_type = "AWS_PROXY"
  integration_uri  = aws_lambda_function.ws_handler.invoke_arn
  integration_method = "POST"
}

resource "aws_apigatewayv2_stage" "prod" {
  api_id      = aws_apigatewayv2_api.ws_api.id
  name        = "prod"
  auto_deploy = true
}
