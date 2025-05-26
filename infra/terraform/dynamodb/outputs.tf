output "user_table_name" {
  description = "DynamoDB table name for users"
  value       = aws_dynamodb_table.users.name
}

output "aws_dynamodb_table_users_arn"{
    description = "ARN of the DynamoDB table for users"
    value       = aws_dynamodb_table.users.arn
}