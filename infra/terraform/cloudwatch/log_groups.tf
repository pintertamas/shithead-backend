
resource "aws_cloudwatch_log_group" "websocket_apigw" {
  name              = "/api-gateway/${var.project_name}-websocket"
  retention_in_days = 14
}
