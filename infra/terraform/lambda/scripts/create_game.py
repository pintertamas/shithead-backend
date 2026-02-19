import json
import boto3
import uuid
import os
import time
from datetime import datetime, timezone

dynamodb = boto3.resource('dynamodb')

DEFAULT_CONFIG = {
    'burnCount':     4,
    'faceDownCount': 3,
    'faceUpCount':   3,
    'handCount':     3,
    'cardRules': {
        '2':  'JOKER',
        '6':  'SMALLER',
        '8':  'TRANSPARENT',
        '9':  'REVERSE',
        '10': 'BURNER'
    },
    'alwaysPlayable': [2, 8],
    'canPlayAgain':   [10]
}

def lambda_handler(event, context):
    user_id = event['requestContext']['authorizer']['claims']['sub']

    body = json.loads(event.get('body') or '{}')
    config = {**DEFAULT_CONFIG, **body.get('config', {})}

    sessions_table = dynamodb.Table(os.environ['GAME_SESSIONS_TABLE'])

    game_id = str(uuid.uuid4())
    ttl = int(time.time()) + 3600  # 1 hour from now

    sessions_table.put_item(Item={
        'game_id':    game_id,
        'user_id':    user_id,
        'status':     'WAITING',
        'config':     config,
        'created_at': datetime.now(timezone.utc).isoformat(),
        'ttl':        ttl
    })

    return {
        'statusCode': 200,
        'body': json.dumps({'gameId': game_id})
    }
