# Abandoned-game janitor. EventBridge invokes the glue function every 5 minutes;
# the glue binary recognises the scheduled event and deletes unfinished games
# (lobbies still waiting for players and started games) that have been idle for
# 15 minutes and have no live WebSocket connection. Connection rows that look live
# are confirmed with API Gateway GetConnection before a game counts as live.
#
# Cost: about 8,640 invocations a month (free tier: 1M requests, 400,000 GB-s),
# no provisioned concurrency and no call to the Java game-api Lambda. EventBridge
# schedules are free.
resource "aws_cloudwatch_event_rule" "abandoned_game_janitor" {
  tags                = { project = var.project_name }
  name                = "${var.project_name}-abandoned-game-janitor"
  description         = "Deletes idle unfinished games (lobbies and started games) that have no live WebSocket connection"
  schedule_expression = "rate(5 minutes)"
}

resource "aws_cloudwatch_event_target" "abandoned_game_janitor" {
  rule      = aws_cloudwatch_event_rule.abandoned_game_janitor.name
  target_id = "abandoned-game-janitor"
  arn       = aws_lambda_function.glue.arn
}

resource "aws_lambda_permission" "abandoned_game_janitor" {
  statement_id  = "AllowEventBridgeAbandonedGameJanitor"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.glue.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.abandoned_game_janitor.arn
}
