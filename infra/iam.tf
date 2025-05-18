resource "aws_iam_role" "lambda_exec" {
  name = "${var.project_name}-lambda-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy_attachment" "lambda_basic" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "dynamo_access" {
  name   = "${var.project_name}-dynamo-policy"
  role   = aws_iam_role.lambda_exec.id
  policy = data.aws_iam_policy_document.dynamo.json
}

data "aws_iam_policy_document" "dynamo" {
  statement {
    actions   = ["dynamodb:PutItem","dynamodb:GetItem","dynamodb:Query","dynamodb:UpdateItem"]
    resources = [
      aws_dynamodb_table.users.arn,
      aws_dynamodb_table.sessions.arn,
    ]
  }
}
