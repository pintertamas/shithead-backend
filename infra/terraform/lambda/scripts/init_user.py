import boto3
import os
from datetime import datetime

def lambda_handler(event, context):
    user_table_name = os.getenv('USER_TABLE_NAME', 'users')
    user_id = event['request']['userAttributes']['sub']
    boto3.resource('dynamodb').Table(user_table_name) \
        .put_item(Item={"created_at": datetime.now().isoformat(), "user_id": user_id, "elo": 1000})
    return event