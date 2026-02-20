data "archive_file" "create_game_zip" {
  type        = "zip"
  source_file = "${path.module}/scripts/create_game.py"
  output_path = "${path.module}/artifacts/create_game_lambda_function.zip"
}

resource "aws_lambda_function" "create_game" {
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-create-game"
  handler       = "create_game.lambda_handler"
  runtime       = "python3.12"

  filename         = data.archive_file.create_game_zip.output_path
  source_code_hash = data.archive_file.create_game_zip.output_base64sha256
  timeout          = 10

  environment {
    variables = {
      GAME_SESSIONS_TABLE = var.aws_dynamodb_table_games_name
      USERS_TABLE         = var.aws_dynamodb_table_users_name
    }
  }
}
