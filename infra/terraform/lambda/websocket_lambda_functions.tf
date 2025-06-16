data "archive_file" "connect_zip" {
  type        = "zip"
  source_file = "${path.module}/scripts/connect.py"
  output_path = "${path.module}/artifacts/connect_lambda_function.zip"
}

data "archive_file" "disconnect_zip" {
  type        = "zip"
  source_file = "${path.module}/scripts/disconnect.py"
  output_path = "${path.module}/artifacts/disconnect_lambda_function.zip"
}

data "archive_file" "default_zip" {
  type        = "zip"
  source_file = "${path.module}/scripts/default.py"
  output_path = "${path.module}/artifacts/default_lambda_function.zip"
}

resource "aws_lambda_function" "ws_connect" {
  tags = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "ws_connect_function"
  handler       = "connect.lambda_handler"
  runtime       = "python3.9"

  filename         = data.archive_file.connect_zip.output_path
  source_code_hash = data.archive_file.connect_zip.output_base64sha256
  timeout          = 10

  environment {
    variables = {
      TABLE_NAME = var.aws_dynamodb_table_ws_connection_name
    }
  }
}

resource "aws_lambda_function" "ws_disconnect" {
  tags = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "ws_disconnect_function"
  handler       = "disconnect.lambda_handler"
  runtime       = "python3.9"

  filename         = data.archive_file.disconnect_zip.output_path
  source_code_hash = data.archive_file.disconnect_zip.output_base64sha256
  timeout          = 10

  environment {
    variables = {
      TABLE_NAME = var.aws_dynamodb_table_ws_connection_name
    }
  }
}

resource "aws_lambda_function" "ws_default" {
  tags = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "ws_default_function"
  handler       = "default.lambda_handler"
  runtime       = "python3.9"

  filename         = data.archive_file.default_zip.output_path
  source_code_hash = data.archive_file.default_zip.output_base64sha256
  timeout          = 10

  environment {
    variables = {
      TABLE_NAME = var.aws_dynamodb_table_ws_connection_name
    }
  }
}
