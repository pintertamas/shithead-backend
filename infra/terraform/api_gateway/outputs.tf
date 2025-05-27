output "api_gateway_game_api_id" {
  description = "The ID of the Game API Gateway"
  value = aws_api_gateway_rest_api.game_api.id
}