locals {
  jar_path = "${path.module}/../../../backend/target/backend-0.0.1-SNAPSHOT.jar"

  java_common_env = {
    GAME_SESSIONS_TABLE  = var.aws_dynamodb_table_games_name
    USERS_TABLE          = var.aws_dynamodb_table_users_name
    WS_CONNECTIONS_TABLE = var.aws_dynamodb_table_ws_connection_name
  }
}

resource "aws_lambda_function" "pickup_pile_ws" {
  tags             = { project = var.project_name }
  role             = aws_iam_role.lambda_exec.arn
  function_name    = "${var.project_name}-pickup-pile-ws"
  handler          = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
  runtime          = "java17"
  filename         = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  timeout          = 30
  memory_size      = 512
  publish          = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = merge(local.java_common_env, {
      SPRING_CLOUD_FUNCTION_DEFINITION = "pickupPileWS"
    })
  }
}

resource "aws_lambda_alias" "pickup_pile_ws_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.pickup_pile_ws.function_name
  function_version = aws_lambda_function.pickup_pile_ws.version
}

resource "aws_lambda_function" "join_game" {
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-join-game"
  handler       = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
  runtime       = "java17"
  filename      = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  timeout       = 30
  memory_size   = 512
  publish       = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = merge(local.java_common_env, {
      SPRING_CLOUD_FUNCTION_DEFINITION = "joinGame"
    })
  }
}

resource "aws_lambda_alias" "join_game_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.join_game.function_name
  function_version = aws_lambda_function.join_game.version
}

resource "aws_lambda_function" "start_game" {
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-start-game"
  handler       = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
  runtime       = "java17"
  filename      = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  timeout       = 30
  memory_size   = 512
  publish       = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = merge(local.java_common_env, {
      SPRING_CLOUD_FUNCTION_DEFINITION = "startGame"
    })
  }
}

resource "aws_lambda_alias" "start_game_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.start_game.function_name
  function_version = aws_lambda_function.start_game.version
}

resource "aws_lambda_function" "get_state" {
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-get-state"
  handler       = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
  runtime       = "java17"
  filename      = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  timeout       = 30
  memory_size   = 512
  publish       = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = merge(local.java_common_env, {
      SPRING_CLOUD_FUNCTION_DEFINITION = "getState"
    })
  }
}

resource "aws_lambda_alias" "get_state_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.get_state.function_name
  function_version = aws_lambda_function.get_state.version
}

resource "aws_lambda_function" "leaderboard_session" {
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-leaderboard-session"
  handler       = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
  runtime       = "java17"
  filename      = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  timeout       = 30
  memory_size   = 512
  publish       = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = merge(local.java_common_env, {
      SPRING_CLOUD_FUNCTION_DEFINITION = "leaderboardSession"
    })
  }
}

resource "aws_lambda_alias" "leaderboard_session_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.leaderboard_session.function_name
  function_version = aws_lambda_function.leaderboard_session.version
}

resource "aws_lambda_function" "leaderboard_top" {
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-leaderboard-top"
  handler       = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
  runtime       = "java17"
  filename      = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  timeout       = 30
  memory_size   = 512
  publish       = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = merge(local.java_common_env, {
      SPRING_CLOUD_FUNCTION_DEFINITION = "leaderboardTop"
    })
  }
}

resource "aws_lambda_alias" "leaderboard_top_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.leaderboard_top.function_name
  function_version = aws_lambda_function.leaderboard_top.version
}

resource "aws_lambda_function" "play_card_ws" {
  tags          = { project = var.project_name }
  role          = aws_iam_role.lambda_exec.arn
  function_name = "${var.project_name}-play-card-ws"
  handler       = "org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest"
  runtime       = "java17"
  filename      = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  timeout       = 30
  memory_size   = 512
  publish       = true

  snap_start { apply_on = "PublishedVersions" }

  environment {
    variables = merge(local.java_common_env, {
      SPRING_CLOUD_FUNCTION_DEFINITION = "playCardWS"
    })
  }
}

resource "aws_lambda_alias" "play_card_ws_live" {
  name             = "LIVE"
  function_name    = aws_lambda_function.play_card_ws.function_name
  function_version = aws_lambda_function.play_card_ws.version
}
