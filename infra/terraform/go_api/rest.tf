# REST API for the Go backend. A single greedy {proxy+} resource forwards every
# method to the Lambda, which does its own routing and CORS handling.

resource "aws_api_gateway_rest_api" "go_api" {
  name        = "${var.project_name}-go-api"
  description = "REST API for the Go backend (side-by-side with the Java/Python API)"

  endpoint_configuration {
    types = ["REGIONAL"]
  }

  tags = { project = var.project_name }
}

resource "aws_api_gateway_authorizer" "go_cognito" {
  name            = "GoCognitoAuthorizer"
  rest_api_id     = aws_api_gateway_rest_api.go_api.id
  type            = "COGNITO_USER_POOLS"
  provider_arns   = [var.cognito_user_pool_arn]
  identity_source = "method.request.header.Authorization"
}

resource "aws_api_gateway_resource" "go_proxy" {
  rest_api_id = aws_api_gateway_rest_api.go_api.id
  parent_id   = aws_api_gateway_rest_api.go_api.root_resource_id
  path_part   = "{proxy+}"
}

resource "aws_api_gateway_method" "go_proxy_any" {
  rest_api_id   = aws_api_gateway_rest_api.go_api.id
  resource_id   = aws_api_gateway_resource.go_proxy.id
  http_method   = "ANY"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = aws_api_gateway_authorizer.go_cognito.id
}

resource "aws_api_gateway_integration" "go_proxy_any" {
  rest_api_id             = aws_api_gateway_rest_api.go_api.id
  resource_id             = aws_api_gateway_resource.go_proxy.id
  http_method             = aws_api_gateway_method.go_proxy_any.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = local.lambda_invoke_arn
}

# CORS preflight is unauthenticated; the Lambda answers it with the CORS headers.
resource "aws_api_gateway_method" "go_proxy_options" {
  rest_api_id   = aws_api_gateway_rest_api.go_api.id
  resource_id   = aws_api_gateway_resource.go_proxy.id
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "go_proxy_options" {
  rest_api_id             = aws_api_gateway_rest_api.go_api.id
  resource_id             = aws_api_gateway_resource.go_proxy.id
  http_method             = aws_api_gateway_method.go_proxy_options.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = local.lambda_invoke_arn
}

# Errors raised by API Gateway itself (auth failures, throttling, timeouts) must
# still carry CORS headers so that the browser can read them.
resource "aws_api_gateway_gateway_response" "go_default_4xx" {
  rest_api_id   = aws_api_gateway_rest_api.go_api.id
  response_type = "DEFAULT_4XX"
  response_parameters = {
    "gatewayresponse.header.Access-Control-Allow-Origin"  = local.cors_headers["Access-Control-Allow-Origin"]
    "gatewayresponse.header.Access-Control-Allow-Headers" = local.cors_headers["Access-Control-Allow-Headers"]
    "gatewayresponse.header.Access-Control-Allow-Methods" = local.cors_headers["Access-Control-Allow-Methods"]
  }
}

resource "aws_api_gateway_gateway_response" "go_default_5xx" {
  rest_api_id   = aws_api_gateway_rest_api.go_api.id
  response_type = "DEFAULT_5XX"
  response_parameters = {
    "gatewayresponse.header.Access-Control-Allow-Origin"  = local.cors_headers["Access-Control-Allow-Origin"]
    "gatewayresponse.header.Access-Control-Allow-Headers" = local.cors_headers["Access-Control-Allow-Headers"]
    "gatewayresponse.header.Access-Control-Allow-Methods" = local.cors_headers["Access-Control-Allow-Methods"]
  }
}

resource "aws_api_gateway_deployment" "go_api" {
  rest_api_id = aws_api_gateway_rest_api.go_api.id

  triggers = {
    redeploy = sha1(join(",", [
      aws_api_gateway_integration.go_proxy_any.id,
      aws_api_gateway_integration.go_proxy_options.id,
      aws_api_gateway_method.go_proxy_any.id,
      aws_api_gateway_method.go_proxy_options.id,
      aws_api_gateway_authorizer.go_cognito.id,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "go_api" {
  rest_api_id   = aws_api_gateway_rest_api.go_api.id
  deployment_id = aws_api_gateway_deployment.go_api.id
  stage_name    = var.stage_name
}

resource "aws_lambda_permission" "go_api_rest" {
  statement_id  = "AllowGoRestInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.go_api.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.go_api.execution_arn}/*/*"
}
