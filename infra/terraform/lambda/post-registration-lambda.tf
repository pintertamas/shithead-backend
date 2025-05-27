####################################################################
# Post user creation Lambda to set the initial ELO in DynamoDB
####################################################################
data "archive_file" "post_registration_zip" {
  type = "zip"
  source_file = "${path.module}/post_registration_lambda_function.py"
  output_path = "${path.module}/artifacts/post_registration_lambda_function.zip"
}

resource "aws_lambda_function" "post_registration" {
  tags = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "post_registration_function"
  handler       = "post_registration_lambda_function.lambda_handler"
  runtime       = "python3.9"

  filename      = data.archive_file.post_registration_zip.output_path
  source_code_hash = data.archive_file.post_registration_zip.output_base64sha256
  timeout       = 10

  environment {
    variables = {
      USER_TABLE_NAME = var.aws_dynamodb_table_users_name
    }
  }
}
