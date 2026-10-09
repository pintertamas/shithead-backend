# WebSocket API for the Go backend. Every route is served by the same Lambda,
# which dispatches on the route key. The $connect route is guarded by a REQUEST
# authorizer that validates the Cognito ID token passed as ?token=.

resource "aws_apigatewayv2_api" "go_ws" {
  name                       = "${var.project_name}-go-ws"
  protocol_type              = "WEBSOCKET"
  route_selection_expression = "$request.body.action"

  tags = { project = var.project_name }
}

resource "aws_apigatewayv2_integration" "go_ws" {
  api_id                 = aws_apigatewayv2_api.go_ws.id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = local.lambda_invoke_arn
  payload_format_version = "1.0"
}

resource "aws_apigatewayv2_authorizer" "go_ws" {
  api_id           = aws_apigatewayv2_api.go_ws.id
  name             = "GoWebSocketJwtAuthorizer"
  authorizer_type  = "REQUEST"
  authorizer_uri   = local.lambda_invoke_arn
  identity_sources = ["route.request.querystring.token"]
}

resource "aws_apigatewayv2_route" "go_connect" {
  api_id             = aws_apigatewayv2_api.go_ws.id
  route_key          = "$connect"
  target             = "integrations/${aws_apigatewayv2_integration.go_ws.id}"
  authorization_type = "CUSTOM"
  authorizer_id      = aws_apigatewayv2_authorizer.go_ws.id
}

resource "aws_apigatewayv2_route" "go_disconnect" {
  api_id    = aws_apigatewayv2_api.go_ws.id
  route_key = "$disconnect"
  target    = "integrations/${aws_apigatewayv2_integration.go_ws.id}"
}

resource "aws_apigatewayv2_route" "go_default" {
  api_id    = aws_apigatewayv2_api.go_ws.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.go_ws.id}"
}

resource "aws_apigatewayv2_route" "go_play" {
  api_id    = aws_apigatewayv2_api.go_ws.id
  route_key = "play"
  target    = "integrations/${aws_apigatewayv2_integration.go_ws.id}"
}

resource "aws_apigatewayv2_route" "go_setup" {
  api_id    = aws_apigatewayv2_api.go_ws.id
  route_key = "setup"
  target    = "integrations/${aws_apigatewayv2_integration.go_ws.id}"
}

resource "aws_apigatewayv2_route" "go_pickup" {
  api_id    = aws_apigatewayv2_api.go_ws.id
  route_key = "pickup"
  target    = "integrations/${aws_apigatewayv2_integration.go_ws.id}"
}

resource "aws_cloudwatch_log_group" "go_ws_access" {
  name              = "/api-gateway/${var.project_name}-go-websocket"
  retention_in_days = 14

  tags = { project = var.project_name }
}

resource "aws_apigatewayv2_stage" "go_ws" {
  api_id = aws_apigatewayv2_api.go_ws.id
  name   = "$default"

  default_route_settings {
    logging_level          = "INFO"
    data_trace_enabled     = false
    throttling_burst_limit = 100
    throttling_rate_limit  = 50
  }

  access_log_settings {
    destination_arn = "${aws_cloudwatch_log_group.go_ws_access.arn}:*"
    format          = "{ \"requestId\":\"$context.requestId\", \"extendedRequestId\":\"$context.extendedRequestId\", \"ip\":\"$context.identity.sourceIp\", \"requestTime\":\"$context.requestTime\", \"routeKey\":\"$context.routeKey\", \"status\":\"$context.status\", \"principalId\":\"$context.authorizer.principalId\", \"integrationError\":\"$context.integrationErrorMessage\", \"responseLength\":\"$context.responseLength\" }"
  }

  auto_deploy = true
}

# The authorizer is invoked through the authorizer resource ARN.
resource "aws_lambda_permission" "go_ws_authorizer" {
  statement_id  = "AllowGoWsAuthorizerInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.go_api.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.go_ws.execution_arn}/authorizers/${aws_apigatewayv2_authorizer.go_ws.id}"
}

resource "aws_lambda_permission" "go_ws_routes" {
  statement_id  = "AllowGoWsRouteInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.go_api.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.go_ws.execution_arn}/*/*"
}
