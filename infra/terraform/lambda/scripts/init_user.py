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
        'elo_score':  1000,
        'leaderboard_pk': 'global',
        'created_at': datetime.now(timezone.utc).isoformat()
    })

    return event
