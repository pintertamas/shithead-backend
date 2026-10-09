locals {
  # Built by CI (or locally) from glue-go/: a linux/arm64 `bootstrap` binary zipped as glue.zip.
  glue_zip = "${path.module}/../../../glue-go/build/glue.zip"
}

# Create-game, WebSocket connect/disconnect/default and the Cognito init-user
# trigger. The Cognito user pool references the init-user trigger, so this
# function must not depend on the pool (hence no pool id env var here).
resource "aws_lambda_function" "glue" {
  tags             = { project = var.project_name }
  role             = aws_iam_role.lambda_exec.arn
  function_name    = "${var.project_name}-glue"
  handler          = "bootstrap"
  runtime          = "provided.al2023"
  architectures    = ["arm64"]
  filename         = local.glue_zip
  source_code_hash = filebase64sha256(local.glue_zip)
  timeout          = 10
  memory_size      = 256

  environment {
    variables = {
      GAME_SESSIONS_TABLE  = var.aws_dynamodb_table_games_name
      USERS_TABLE          = var.aws_dynamodb_table_users_name
      WS_CONNECTIONS_TABLE = var.aws_dynamodb_table_ws_connection_name
    }
  }
}

# WebSocket REQUEST authorizer: verifies the Cognito ID token and denies blocked users.
resource "aws_lambda_function" "authorizer" {
  tags             = { project = var.project_name }
  role             = aws_iam_role.lambda_exec.arn
  function_name    = "${var.project_name}-authorizer"
  handler          = "bootstrap"
  runtime          = "provided.al2023"
  architectures    = ["arm64"]
  filename         = local.glue_zip
  source_code_hash = filebase64sha256(local.glue_zip)
  timeout          = 10
  memory_size      = 256

  environment {
    variables = {
      COGNITO_USER_POOL_ID  = var.cognito_user_pool_id
      COGNITO_APP_CLIENT_ID = var.cognito_user_pool_client_id
      REGION                = var.aws_region
      USERS_TABLE           = var.aws_dynamodb_table_users_name
    }
  }
}
