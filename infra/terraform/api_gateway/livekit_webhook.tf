# LiveKit room webhook: POST /livekit/webhook, served by the glue Lambda. LiveKit calls it
# server to server, so there is no Cognito authorizer (the glue verifies the LiveKit HS256
# signature) and no CORS OPTIONS method. create_game_invoke_arn is the glue function's ARN
# (see lambda/outputs.tf), and aws_lambda_permission.api_gateway already allows every REST
# path of that function. Its integration id is in the aws_api_gateway_deployment triggers.

resource "aws_api_gateway_resource" "livekit" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_rest_api.game_api.root_resource_id
  path_part   = "livekit"
}

resource "aws_api_gateway_resource" "livekit_webhook" {
  rest_api_id = aws_api_gateway_rest_api.game_api.id
  parent_id   = aws_api_gateway_resource.livekit.id
  path_part   = "webhook"
}

resource "aws_api_gateway_method" "post_livekit_webhook" {
  rest_api_id   = aws_api_gateway_rest_api.game_api.id
  resource_id   = aws_api_gateway_resource.livekit_webhook.id
  http_method   = "POST"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "post_livekit_webhook" {
  rest_api_id             = aws_api_gateway_rest_api.game_api.id
  resource_id             = aws_api_gateway_resource.livekit_webhook.id
  http_method             = aws_api_gateway_method.post_livekit_webhook.http_method
  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${var.create_game_invoke_arn}/invocations"
}
