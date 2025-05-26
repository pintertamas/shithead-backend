####################################################################
# Post user creation Lambda to set the initial ELO in DynamoDB
####################################################################
data "archive_file" "post_confirmation_zip" {
  type = "zip"
  source {
    filename = "lambda_function.py"
    content  = <<EOF
import boto3
from datetime import datetime

def handler(event, context):
    user_id = event['request']['userAttributes']['sub']
    boto3.resource('dynamodb').Table("users") \
        .put_item(Item={"created_at": datetime.now().isoformat(), "user_id": user_id, "elo": 1000})
    return event
EOF
  }
  output_path = "${path.module}/artifacts/post_confirmation.zip"
}

resource "aws_lambda_function" "post_confirmation" {
  tags = { project = var.project_name }
  function_name = "cognito-post-confirmation"
  runtime       = "python3.9"
  handler       = "lambda_function.handler"
  role          = aws_iam_role.lambda_exec.arn
  filename      = data.archive_file.post_confirmation_zip.output_path
  source_code_hash = data.archive_file.post_confirmation_zip.output_base64sha256
  timeout       = 10
}
