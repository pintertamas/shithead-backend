####################################################################
# Post user creation Lambda to set the initial ELO in DynamoDB
####################################################################
data "archive_file" "init_user_zip" {
  type = "zip"
  source_file = "${path.module}/scripts/init_user.py"
  output_path = "${path.module}/artifacts/init_user_lambda_function.zip"
}

resource "aws_lambda_function" "init_user_function" {
  tags = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "init_user_function"
  handler       = "init_user.lambda_handler"
  runtime       = "python3.9"

  filename      = data.archive_file.init_user_zip.output_path
  source_code_hash = data.archive_file.init_user_zip.output_base64sha256
  timeout       = 10

  environment {
    variables = {
      USER_TABLE_NAME = var.aws_dynamodb_table_users_name
    }
  }
}
