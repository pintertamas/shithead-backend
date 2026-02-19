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
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-ws-connect"
  handler       = "connect.lambda_handler"
  runtime       = "python3.12"

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
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-ws-disconnect"
  handler       = "disconnect.lambda_handler"
  runtime       = "python3.12"

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
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-ws-default"
  handler       = "default.lambda_handler"
  runtime       = "python3.12"

  filename         = data.archive_file.default_zip.output_path
  source_code_hash = data.archive_file.default_zip.output_base64sha256
  timeout          = 10

  environment {
    variables = {
      TABLE_NAME = var.aws_dynamodb_table_ws_connection_name
    }
  }
}
