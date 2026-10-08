locals {
  account_management_jar = "${path.module}/../../../backend/target/backend-0.0.1-SNAPSHOT.jar"
}

resource "aws_lambda_function" "account_management" {
  tags             = { project = var.project_name }
  role             = aws_iam_role.account_management_exec.arn
  function_name    = "${var.project_name}-account-management"
  handler          = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
  runtime          = "java17"
  filename         = local.account_management_jar
  source_code_hash = filebase64sha256(local.account_management_jar)
  timeout          = 60
  memory_size      = 512
  publish          = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = {
      GAME_SESSIONS_TABLE              = var.aws_dynamodb_table_games_name
      USERS_TABLE                      = var.aws_dynamodb_table_users_name
      WS_CONNECTIONS_TABLE             = var.aws_dynamodb_table_ws_connection_name
      WS_MANAGEMENT_ENDPOINT           = replace(var.websocket_api_endpoint, "wss://", "https://")
      SPRING_CLOUD_FUNCTION_DEFINITION = "accountManagement"
    }
  }
}

resource "aws_lambda_alias" "account_management_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.account_management.function_name
  function_version = aws_lambda_function.account_management.version
}
