# DynamoDB table for users
resource "aws_dynamodb_table" "users" {
  tags = { project = var.project_name }
  name = "${var.project_name}-users"
  billing_mode = "PAY_PER_REQUEST"

  hash_key = "user_id"

  attribute {
    name = "user_id"
    type = "S"
  }
  attribute {
    name = "username"
    type = "S"
  }
  attribute {
    name = "leaderboard_pk"
    type = "S"
  }
  attribute {
    name = "elo_score"
    type = "N"
  }

  global_secondary_index {
    name            = "username-index"
    hash_key        = "username"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "leaderboard-index"
    hash_key        = "leaderboard_pk"
    range_key       = "elo_score"
    projection_type = "ALL"
  }
}

# DynamoDB table for game sessions
resource "aws_dynamodb_table" "games" {
  tags = { project = var.project_name }
  name = "${var.project_name}-game-sessions"
  billing_mode = "PAY_PER_REQUEST"

  hash_key = "game_id"

  attribute {
    name = "game_id"
    type = "S"
  }

  attribute {
    name = "user_id"
    type = "S"
  }

  attribute {
    name = "created_at"
    type = "S"
  }

  global_secondary_index {
    name            = "user_id-index"
    hash_key        = "user_id"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "created_at-index"
    hash_key        = "created_at"
    projection_type = "ALL"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }
}

resource "aws_dynamodb_table" "ws_connections" {
  name         = "${var.project_name}-connection-registry"
  billing_mode = "PAY_PER_REQUEST"

  hash_key = "connection_id"

  attribute {
    name = "connection_id"
    type = "S"
  }

  attribute {
    name = "game_session_id"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  global_secondary_index {
    name               = "game_session_id-index"
    hash_key           = "game_session_id"
    projection_type    = "ALL"
  }
}
