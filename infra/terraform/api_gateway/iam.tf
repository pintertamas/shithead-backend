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