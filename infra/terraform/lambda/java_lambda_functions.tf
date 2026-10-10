locals {
  jar_path = "${path.module}/../../../backend/target/backend-0.0.1-SNAPSHOT.jar"
}

# One Java Lambda serves every REST and WebSocket route that needs the game
# logic or the profile/admin code. The route table lives in
# backend/src/main/java/com/tamaspinter/backend/config/ApiRoutes.java.
resource "aws_lambda_function" "game_api" {
  tags             = { project = var.project_name }
  role             = aws_iam_role.game_api_exec.arn
  function_name    = "${var.project_name}-game-api"
  handler          = "com.tamaspinter.backend.LambdaHandler::handleRequest"
  runtime          = "java17"
  filename         = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  timeout          = 60
  memory_size      = 1024
  publish          = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = {
      GAME_SESSIONS_TABLE              = var.aws_dynamodb_table_games_name
      USERS_TABLE                      = var.aws_dynamodb_table_users_name
      WS_CONNECTIONS_TABLE             = var.aws_dynamodb_table_ws_connection_name
      WS_MANAGEMENT_ENDPOINT           = format("%s/$default", replace(var.websocket_api_endpoint, "wss://", "https://"))
      SPRING_CLOUD_FUNCTION_DEFINITION = "gameApi"
      # Account deletion (DELETE /profile) removes the Cognito user from this pool.
      COGNITO_USER_POOL_ID             = var.cognito_user_pool_id
      # Voice chat (LiveKit). Empty values keep the feature off; see backend VoiceTokenHandler.
      LIVEKIT_URL                      = var.livekit_url
      LIVEKIT_API_KEY                  = var.livekit_api_key
      LIVEKIT_API_SECRET               = var.livekit_api_secret
    }
  }
}

resource "aws_lambda_alias" "game_api_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.game_api.function_name
  function_version = aws_lambda_function.game_api.version
}

resource "aws_iam_role" "game_api_exec" {
  name = "${var.project_name}-game-api-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

# Union of the permissions the former per-handler roles and the account
# management role granted.
resource "aws_iam_role_policy" "game_api" {
  name = "${var.project_name}-game-api-policy"
  role = aws_iam_role.game_api_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "arn:aws:logs:*:*:*"
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:BatchGetItem",
          "dynamodb:Query",
          "dynamodb:Scan",
          "dynamodb:TransactWriteItems"
        ]
        Resource = [
          var.aws_dynamodb_table_users_arn,
          "${var.aws_dynamodb_table_users_arn}/index/leaderboard-index"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query",
          "dynamodb:Scan"
        ]
        Resource = [
          var.aws_dynamodb_table_games_arn,
          "${var.aws_dynamodb_table_games_arn}/index/user_id-index"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query",
          "dynamodb:Scan"
        ]
        Resource = [
          var.aws_dynamodb_table_ws_connections_arn,
          "${var.aws_dynamodb_table_ws_connections_arn}/index/game_session_id-index"
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["execute-api:ManageConnections"]
        Resource = "${var.aws_apigateway_ws_execution_arn}/*/*/@connections/*"
      },
      {
        # DELETE /profile: removes only the signed-in user's own Cognito account.
        Effect   = "Allow"
        Action   = ["cognito-idp:AdminDeleteUser"]
        Resource = var.cognito_user_pool_arn
      }
    ]
  })
}
