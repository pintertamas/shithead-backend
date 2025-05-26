# For provisioning the default ELO score for new users
resource "aws_iam_role" "lambda_exec" {
  name = "${var.project_name}-cognito-post-confirmation-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Action = "sts:AssumeRole",
      Effect = "Allow",
      Principal = {
        Service = "lambda.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy" "lambda_ddb" {
  name = "PostConfirmationDDBPolicy"
  role = aws_iam_role.lambda_exec.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["dynamodb:PutItem"]
      Resource = var.aws_dynamodb_table_users_arn
    }]
  })
}
