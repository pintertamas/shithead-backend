resource "aws_lambda_permission" "api_gateway" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = var.create_game_function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.game_api.execution_arn}/*/*"
}

resource "aws_lambda_permission" "allow_connect" {
  statement_id  = "AllowInvokeConnect"
  action        = "lambda:InvokeFunction"
  function_name = var.aws_lambda_function_ws_connect_function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/$connect"
}

resource "aws_lambda_permission" "allow_disconnect" {
  statement_id  = "AllowInvokeDisconnect"
  action        = "lambda:InvokeFunction"
  function_name = var.aws_lambda_function_ws_disconnect_function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/$disconnect"
}

resource "aws_lambda_permission" "allow_default" {
  statement_id  = "AllowInvokeDefault"
  action        = "lambda:InvokeFunction"
  function_name = var.aws_lambda_function_ws_default_function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/$default"
}

resource "aws_lambda_permission" "allow_ws_authorizer" {
  statement_id  = "AllowInvokeWsAuthorizer"
  action        = "lambda:InvokeFunction"
  function_name = var.aws_lambda_function_ws_authorizer_function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/connect"
}

resource "aws_lambda_permission" "allow_join_game" {
  statement_id  = "AllowAPIGatewayInvokeJoinGame"
  action        = "lambda:InvokeFunction"
  function_name = var.join_game_function_name
  qualifier     = "LIVE"
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.game_api.execution_arn}/*/*"
}

resource "aws_lambda_permission" "allow_start_game" {
  statement_id  = "AllowAPIGatewayInvokeStartGame"
  action        = "lambda:InvokeFunction"
  function_name = var.start_game_function_name
  qualifier     = "LIVE"
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.game_api.execution_arn}/*/*"
}

resource "aws_lambda_permission" "allow_get_state" {
  statement_id  = "AllowAPIGatewayInvokeGetState"
  action        = "lambda:InvokeFunction"
  function_name = var.get_state_function_name
  qualifier     = "LIVE"
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.game_api.execution_arn}/*/*"
}

resource "aws_lambda_permission" "allow_leaderboard_session" {
  statement_id  = "AllowAPIGatewayInvokeLeaderboardSession"
  action        = "lambda:InvokeFunction"
  function_name = var.leaderboard_session_function_name
  qualifier     = "LIVE"
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.game_api.execution_arn}/*/*"
}

resource "aws_lambda_permission" "allow_leaderboard_top" {
  statement_id  = "AllowAPIGatewayInvokeLeaderboardTop"
  action        = "lambda:InvokeFunction"
  function_name = var.leaderboard_top_function_name
  qualifier     = "LIVE"
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.game_api.execution_arn}/*/*"
}

resource "aws_lambda_permission" "allow_play_card_ws" {
  statement_id  = "AllowWebSocketInvokePlayCard"
  action        = "lambda:InvokeFunction"
  function_name = var.play_card_ws_function_name
  qualifier     = "LIVE"
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/play"
}

resource "aws_lambda_permission" "allow_pickup_pile_ws" {
  statement_id  = "AllowWebSocketInvokePickupPile"
  action        = "lambda:InvokeFunction"
  function_name = var.pickup_pile_ws_function_name
  qualifier     = "LIVE"
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.game_ws.execution_arn}/*/pickup"
}
