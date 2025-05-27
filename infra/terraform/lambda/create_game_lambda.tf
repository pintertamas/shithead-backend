data "archive_file" "create_game_zip" {
  type = "zip"
  source_file = "${path.module}/create_game_lambda_function.py"
  output_path = "${path.module}/artifacts/create_game_lambda_function.zip"
}

resource "aws_lambda_function" "create_game" {
  function_name = "create_game_function"
  role          = aws_iam_role.lambda_exec.arn
  handler       = "create_game_lambda_function.lambda_handler"
  runtime       = "python3.9"

  filename      = data.archive_file.create_game_zip.output_path
  source_code_hash = data.archive_file.create_game_zip.output_base64sha256
  timeout       = 10

  environment {
    variables = { // TODO: Replace with your actual values
      ECS_CLUSTER_NAME = "your-ecs-cluster-name"
      TASK_DEFINITION  = "your-task-definition"
      SUBNETS          = "subnet-xxxxxxx,subnet-yyyyyyy"
      CONTAINER_NAME   = "your-container-name"
      DYNAMODB_TABLE   = var.aws_dynamodb_table_games_name
    }
  }
}
