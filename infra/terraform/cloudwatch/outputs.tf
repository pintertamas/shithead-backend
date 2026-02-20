
output "aws_cloudwatch_log_group_websocket_apigw_arn" {
  description = "ARN of the CloudWatch log group for WebSocket API Gateway"
  value       = aws_cloudwatch_log_group.websocket_apigw.arn
}
