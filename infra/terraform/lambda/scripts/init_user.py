import boto3
import os
from datetime import datetime, timezone

dynamodb = boto3.resource('dynamodb')

def lambda_handler(event, context):
    attrs = event['request']['userAttributes']
    user_id  = attrs['sub']
    username = attrs.get('preferred_username') or attrs.get('email')

    dynamodb.Table(os.environ['USER_TABLE_NAME']).put_item(Item={
        'user_id':    user_id,
        'username':   username,
        'elo':        1000,
        'created_at': datetime.now(timezone.utc).isoformat()
    })

    return event
