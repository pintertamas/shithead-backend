resource "aws_iam_role" "lambda_exec" {
  name = "${var.project_name}-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = "sts:AssumeRole",
        Effect = "Allow",
        Principal = {
          Service = "lambda.amazonaws.com"
        }
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
        Effect   = "Allow"
        Action = ["dynamodb:PutItem"]
        Resource = var.aws_dynamodb_table_users_arn
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:Scan",
          "dynamodb:PutItem",
          "dynamodb:Query"
        ]
        Resource = [
          var.aws_dynamodb_table_games_arn,
          "${var.aws_dynamodb_table_games_arn}/index/user_id-index"
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy" "lambda_policy" {
  name = "lambda_policy"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Action = [
          "ecs:RunTask",
          "ecs:ListTasks",
          "ecs:DescribeTasks",
          "ecs:DescribeTaskDefinition",
          "iam:PassRole"
        ],
        Effect   = "Allow",
        Resource = "*"
      },
      {
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ],
        Effect   = "Allow",
        Resource = "*"
      },
      {
        Action = [
          "elasticloadbalancing:*",
          "apigateway:CreateApi",
          "ssm:PutParameter"
        ],
        Effect   = "Allow",
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:DeleteItem",
          "dynamodb:Scan"
        ]
        Resource = var.aws_dynamodb_table_ws_connections_arn
      },
      {
        Effect   = "Allow"
        Action = ["execute-api:ManageConnections"]
        Resource = "${var.aws_apigateway_ws_execution_arn}/*/*/@connections/*"
      }
    ]
  })
}