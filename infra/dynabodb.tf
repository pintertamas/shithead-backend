resource "aws_dynamodb_table" "users" {
  name           = "${var.project_name}-users"
  hash_key       = "PK"
  billing_mode   = "PAY_PER_REQUEST"
  attribute {
    name = "PK"
    type = "S"
  }
}

resource "aws_dynamodb_table" "sessions" {
  name           = "${var.project_name}-sessions"
  hash_key       = "PK"
  range_key      = "SK"
  billing_mode   = "PAY_PER_REQUEST"
  ttl {
    attribute_name = "expires_at"
    enabled        = true
  }
  attribute {
    name = "PK"
    type = "S"
  }
  attribute {
    name = "SK"
    type = "S"
  }
}
