# DynamoDB table for users
resource "aws_dynamodb_table" "users" {
  tags = { project = var.project_name }
  name = "users"
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
