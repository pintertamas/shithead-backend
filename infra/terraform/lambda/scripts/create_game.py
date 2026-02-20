import json
import boto3
import os
import time
from datetime import datetime, timezone
import random
import string

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
    users_table = dynamodb.Table(os.environ['USERS_TABLE'])

    game_id = generate_session_id(sessions_table)
    ttl = int(time.time()) + 3600  # 1 hour from now
    user = users_table.get_item(Key={'user_id': user_id}).get('Item', {})
    username = user.get('username', 'Unknown')

    sessions_table.put_item(Item={
        'game_id':         game_id,
        'user_id':         user_id,
        'players':         [{
            'playerId': user_id,
            'username': username,
            'hand':     [],
            'faceUp':   [],
            'faceDown': [],
            'out':      False
        }],
        'discardPile':     [],
        'deck':            [],
        'currentPlayerId': user_id,
        'started':         False,
        'finished':        False,
        'shitheadId':      None,
        'config':          config,
        'created_at':      datetime.now(timezone.utc).isoformat(),
        'ttl':             ttl
    })

    return {
        'statusCode': 200,
        'body': json.dumps({'sessionId': game_id})
    }

def generate_session_id(table):
    alphabet = string.ascii_uppercase + string.digits
    for _ in range(10):
        code = ''.join(random.choice(alphabet) for _ in range(6))
        if table.get_item(Key={'game_id': code}).get('Item') is None:
            return code
    return ''.join(random.choice(alphabet) for _ in range(6))
