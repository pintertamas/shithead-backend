# Side-by-side Go backend: one Lambda that serves the REST API, the WebSocket API
# and the WebSocket authorizer. Every resource here is new; nothing in the
# existing Java/Python stack is referenced or changed.

locals {
  lambda_name            = "${var.project_name}-go-api"
  lambda_invoke_arn      = "arn:aws:apigateway:${var.aws_region}:lambda:path/2015-03-31/functions/${aws_lambda_function.go_api.arn}/invocations"
  ws_management_endpoint = "${replace(aws_apigatewayv2_api.go_ws.api_endpoint, "wss://", "https://")}/${aws_apigatewayv2_stage.go_ws.name}"
  cors_headers = {
    "Access-Control-Allow-Origin"  = "'*'"
    "Access-Control-Allow-Headers" = "'Content-Type,Authorization'"
    "Access-Control-Allow-Methods" = "'POST,GET,PUT,OPTIONS'"
  }
}

resource "aws_iam_role" "go_api" {
  name = "${var.project_name}-go-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })

  tags = { project = var.project_name }
}

resource "aws_iam_role_policy" "go_api_logs" {
  name = "${var.project_name}-go-lambda-logs-policy"
  role = aws_iam_role.go_api.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"]
      Resource = "arn:aws:logs:*:*:*"
    }]
  })
}

resource "aws_iam_role_policy" "go_api_dynamodb" {
  name = "${var.project_name}-go-lambda-ddb-policy"
  role = aws_iam_role.go_api.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:UpdateItem",
        "dynamodb:DeleteItem",
        "dynamodb:Query",
        "dynamodb:Scan",
        "dynamodb:BatchGetItem",
        "dynamodb:TransactWriteItems",
      ]
      Resource = [
        var.users_table_arn,
        "${var.users_table_arn}/index/*",
        var.games_table_arn,
        "${var.games_table_arn}/index/*",
        var.ws_connections_table_arn,
        "${var.ws_connections_table_arn}/index/*",
      ]
    }]
  })
}

resource "aws_iam_role_policy" "go_api_apigw" {
  name = "${var.project_name}-go-lambda-apigw-policy"
  role = aws_iam_role.go_api.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["execute-api:ManageConnections"]
      Resource = "${aws_apigatewayv2_api.go_ws.execution_arn}/*/*/@connections/*"
    }]
  })
}

resource "aws_lambda_function" "go_api" {
  function_name = local.lambda_name
  role          = aws_iam_role.go_api.arn
  runtime       = "provided.al2023"
  handler       = "bootstrap"
  architectures = ["arm64"]
  memory_size   = 256
  timeout       = 30

  filename         = var.artifact_path
  source_code_hash = filebase64sha256(var.artifact_path)

  environment {
    variables = {
      GAME_SESSIONS_TABLE    = var.games_table_name
      USERS_TABLE            = var.users_table_name
      WS_CONNECTIONS_TABLE   = var.ws_connections_table_name
      WS_MANAGEMENT_ENDPOINT = local.ws_management_endpoint
      COGNITO_USER_POOL_ID   = var.cognito_user_pool_id
      COGNITO_APP_CLIENT_ID  = var.cognito_user_pool_client_id
      REGION                 = var.aws_region
    }
  }

  tags = { project = var.project_name }
}
