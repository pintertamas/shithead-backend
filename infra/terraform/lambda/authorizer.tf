resource "null_resource" "deps" {
  # re-run whenever requirements.txt changes
  triggers = {
    reqs_sha = sha256(file("${path.module}/scripts/requirements.txt"))
  }

  provisioner "local-exec" {
    command = "python ${path.module}/scripts/package.py"

  }
}

data "archive_file" "authorizer_zip" {
  type        = "zip"
  source_dir  = "${path.module}/build"
  output_path = "${path.module}/artifacts/ws_authorizer_lambda_function.zip"
  depends_on  = [null_resource.deps]
}

resource "aws_lambda_function" "ws_authorizer" {
  tags = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-ws-authorizer"
  handler       = "ws_authorizer.lambda_handler"
  runtime       = "python3.12"

  filename         = data.archive_file.authorizer_zip.output_path
  source_code_hash = data.archive_file.authorizer_zip.output_base64sha256
  timeout          = 10

  environment {
    variables = {
      COGNITO_USER_POOL_ID  = var.cognito_user_pool_id
      COGNITO_APP_CLIENT_ID = var.cognito_user_pool_client_id
      REGION                = var.aws_region
    }
  }
}

