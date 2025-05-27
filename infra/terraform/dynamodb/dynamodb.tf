# DynamoDB table for users
resource "aws_dynamodb_table" "users" {
  tags = { project = var.project_name }
  name = "${var.project_name}-users"
  billing_mode = "PAY_PER_REQUEST"

  # Primary key is still only user_id
  hash_key = "user_id"

  attribute {
    name = "user_id"
    type = "S"
  }
  attribute {
    name = "username"
    type = "S"
  }

  global_secondary_index {
    name            = "username-index"
    hash_key        = "username"
    projection_type = "ALL"
  }
}

# DynamoDB table for game sessions
resource "aws_dynamodb_table" "games" {
  tags = { project = var.project_name }
  name = "${var.project_name}-game-sessions"
  billing_mode = "PAY_PER_REQUEST"

  # Primary key is still only game_id
  hash_key = "game_id"

  attribute {
    name = "game_id"
    type = "S"
  }

  attribute {
    name = "created_at"
    type = "S"
  }

  global_secondary_index {
    name            = "created_at-index"
    hash_key        = "created_at"
    projection_type = "ALL"
  }
}
