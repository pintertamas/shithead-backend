output "user_table_name" {
  description = "DynamoDB table name for users"
  value       = aws_dynamodb_table.users.name
}

output "game_table_name" {
  description = "DynamoDB table name for game sessions"
  value       = aws_dynamodb_table.games.name
}

output "aws_dynamodb_table_users_arn"{
  description = "ARN of the DynamoDB table for users"
  value       = aws_dynamodb_table.users.arn
}

output "aws_dynamodb_table_games_arn"{
  description = "ARN of the DynamoDB table for games"
  value       = aws_dynamodb_table.games.arn
}

output "aws_dynamodb_table_ws_connections_arn" {
    description = "ARN of the DynamoDB table for WebSocket connections"
    value       = aws_dynamodb_table.ws_connections.arn
}

output "aws_dynamodb_table_ws_connections_name" {
    description = "Name of the DynamoDB table for WebSocket connections"
    value       = aws_dynamodb_table.ws_connections.name
}
