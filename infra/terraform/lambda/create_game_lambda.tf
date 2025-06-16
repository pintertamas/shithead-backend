data "archive_file" "create_game_zip" {
  type        = "zip"
  source_file = "${path.module}/scripts/create_game.py"
  output_path = "${path.module}/artifacts/create_game_lambda_function.zip"
}

resource "aws_lambda_function" "create_game" {
  tags = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "create_game_function"
  handler       = "create_game.lambda_handler"
  runtime       = "python3.9"

  filename         = data.archive_file.create_game_zip.output_path
  source_code_hash = data.archive_file.create_game_zip.output_base64sha256
  timeout          = 10

  environment {
    variables = {
      ECS_CLUSTER_NAME     = var.ecs_cluster_name
      TASK_DEFINITION      = var.ecs_task_arn
      SUBNETS = join(",", var.subnets)
      CONTAINER_NAME       = var.game_container_name
      IDLE_TIMEOUT_MINUTES = var.idle_timeout_minutes
      GAME_SESSIONS_TABLE  = var.aws_dynamodb_table_games_name
      USERS_TABLE          = var.aws_dynamodb_table_users_name
    }
  }
}
