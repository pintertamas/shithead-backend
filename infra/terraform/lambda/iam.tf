resource "aws_iam_role" "lambda_exec" {
  name = "${var.project_name}-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action    = "sts:AssumeRole",
        Effect    = "Allow",
        Principal = { Service = "lambda.amazonaws.com" }
      }
    ]
  })
}

resource "aws_iam_role_policy" "lambda_logs" {
  name = "${var.project_name}-lambda-logs-policy"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "arn:aws:logs:*:*:*"
      }
    ]
  })
}

resource "aws_iam_role_policy" "lambda_ddb" {
  name = "${var.project_name}-lambda-ddb-policy"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:BatchGetItem",
          "dynamodb:Query"
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
          "dynamodb:Query"
        ]
        Resource = [
          var.aws_dynamodb_table_games_arn,
          "${var.aws_dynamodb_table_games_arn}/index/user_id-index"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query"
        ]
        Resource = [
          var.aws_dynamodb_table_ws_connections_arn,
          "${var.aws_dynamodb_table_ws_connections_arn}/index/game_session_id-index"
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy" "lambda_apigw" {
  name = "${var.project_name}-lambda-apigw-policy"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["execute-api:ManageConnections"]
        Resource = "${var.aws_apigateway_ws_execution_arn}/*/*/@connections/*"
      }
    ]
  })
}
