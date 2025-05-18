resource "aws_lambda_function" "ws_handler" {
  function_name = "${var.project_name}-ws-handler"
  role          = aws_iam_role.lambda_exec.arn
  handler       = "com.yourname.Handler::handleRequest"
  runtime       = "java17"
  filename      = "build/libs/${var.project_name}-backend.jar"
  memory_size   = 512
  timeout       = 10
}
