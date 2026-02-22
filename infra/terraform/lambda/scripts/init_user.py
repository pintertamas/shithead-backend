import boto3
import os
from datetime import datetime, timezone
from decimal import Decimal

dynamodb = boto3.resource('dynamodb')

def lambda_handler(event, context):
    attrs = event['request']['userAttributes']
    user_id  = attrs['sub']
    username = attrs.get('preferred_username') or attrs.get('email')

    # Upsert: always refresh username, but only initialise elo/rank on first login
    dynamodb.Table(os.environ['USER_TABLE_NAME']).update_item(
        Key={'user_id': user_id},
        UpdateExpression=(
            'SET username = :u, '
            'leaderboard_pk = if_not_exists(leaderboard_pk, :lpk), '
            'elo_score = if_not_exists(elo_score, :elo), '
            'created_at = if_not_exists(created_at, :ca)'
        ),
        ExpressionAttributeValues={
            ':u':   username,
            ':lpk': 'global',
            ':elo': Decimal('1000'),
            ':ca':  datetime.now(timezone.utc).isoformat()
        }
    )

    return event
