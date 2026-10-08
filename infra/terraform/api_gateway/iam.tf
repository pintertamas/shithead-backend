data "aws_iam_policy_document" "apigateway_cloudwatch_assume_role" {
  statement {
    effect = "Allow"
    principals {
      type        = "Service"
      identifiers = ["apigateway.amazonaws.com"]
    }
    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "apigateway_cloudwatch" {
  name               = "api-gateway-cloudwatch-global"
  assume_role_policy = data.aws_iam_policy_document.apigateway_cloudwatch_assume_role.json
}

resource "aws_iam_role_policy_attachment" "apigateway_cloudwatch" {
  role       = aws_iam_role.apigateway_cloudwatch.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs"
}

resource "aws_api_gateway_account" "cloudwatch" {
  cloudwatch_role_arn = aws_iam_role.apigateway_cloudwatch.arn
  depends_on          = [aws_iam_role_policy_attachment.apigateway_cloudwatch]
}

resource "aws_lambda_permission" "allow_apigw_connect" {
  statement_id  = "AllowGameWsConnect"
  action        = "lambda:InvokeFunction"
  function_name = var.aws_lambda_function_ws_connect_function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/$connect"
}

resource "aws_lambda_permission" "allow_apigw_disconnect" {
  statement_id  = "AllowGameWsDisconnect"
  action        = "lambda:InvokeFunction"
  function_name = var.aws_lambda_function_ws_disconnect_function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/$disconnect"
}

resource "aws_lambda_permission" "allow_apigw_default" {
  statement_id  = "AllowGameWsDefault"
  action        = "lambda:InvokeFunction"
  function_name = var.aws_lambda_function_ws_default_function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/$default"
}
