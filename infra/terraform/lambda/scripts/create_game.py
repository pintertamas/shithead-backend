import json
import boto3
import os
import time
from datetime import datetime, timezone
import random
import string

dynamodb = boto3.resource('dynamodb')

DEFAULT_CONFIG = {
    'decksCount':    1,
    'burnCount':     4,
    'faceDownCount': 3,
    'faceUpCount':   3,
    'handCount':     3,
    'allowMixedHandAndFaceUpWhenDeckEmpty': False,
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

CARD_RULES = {'DEFAULT', 'JOKER', 'SMALLER', 'TRANSPARENT', 'REVERSE', 'BURNER'}

def normalize_config(requested):
    if not isinstance(requested, dict):
        raise ValueError('config must be an object')
    config = {**DEFAULT_CONFIG, **requested}
    decks_count = config.get('decksCount')
    if decks_count not in (1, 2):
        raise ValueError('decksCount must be 1 or 2')
    config['decksCount'] = decks_count
    config['burnCount'] = 4 if decks_count == 1 else 6

    requested_rules = requested.get('cardRules')
    if requested_rules is not None:
        if not isinstance(requested_rules, dict):
            raise ValueError('cardRules must be an object')
        rules = {}
        for value in range(2, 15):
            rule = requested_rules.get(str(value), 'DEFAULT')
            if rule not in CARD_RULES:
                raise ValueError('cardRules contains an unsupported rule')
            rules[str(value)] = rule
        config['cardRules'] = rules
        config['alwaysPlayable'] = [
            int(value) for value, rule in rules.items() if rule in ('JOKER', 'TRANSPARENT')
        ]
        config['canPlayAgain'] = [
            int(value) for value, rule in rules.items() if rule == 'BURNER'
        ]
    return config

def cleanup_old_sessions(sessions_table, user_id):
    """Remove user from any non-started sessions they own or are in."""
    # Find sessions owned by this user
    resp = sessions_table.query(
        IndexName='user_id-index',
        KeyConditionExpression='user_id = :uid',
        ExpressionAttributeValues={':uid': user_id}
    )
    for item in resp.get('Items', []):
        if item.get('started'):
            continue
        game_id = item['game_id']
        players = item.get('players', [])
        remaining = [p for p in players if p.get('playerId') != user_id]
        if not remaining:
            sessions_table.delete_item(Key={'game_id': game_id})
        else:
            new_owner = remaining[0]['playerId']
            sessions_table.update_item(
                Key={'game_id': game_id},
                UpdateExpression='SET players = :p, user_id = :o',
                ExpressionAttributeValues={':p': remaining, ':o': new_owner}
            )

def lambda_handler(event, context):
    user_id = event['requestContext']['authorizer']['claims']['sub']

    body = json.loads(event.get('body') or '{}')
    try:
        config = normalize_config(body.get('config', {}))
    except (TypeError, ValueError):
        return {
            'statusCode': 400,
            'headers': {
                'Access-Control-Allow-Origin': '*',
                'Access-Control-Allow-Headers': 'Content-Type,Authorization',
                'Access-Control-Allow-Methods': 'POST,OPTIONS',
                'Content-Type': 'application/json'
            },
            'body': json.dumps({'error': 'Invalid game configuration'})
        }

    sessions_table = dynamodb.Table(os.environ['GAME_SESSIONS_TABLE'])
    users_table = dynamodb.Table(os.environ['USERS_TABLE'])

    cleanup_old_sessions(sessions_table, user_id)

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
        'headers': {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Headers': 'Content-Type,Authorization',
            'Access-Control-Allow-Methods': 'POST,OPTIONS',
            'Content-Type': 'application/json'
        },
        'body': json.dumps({'sessionId': game_id})
    }

def generate_session_id(table):
    alphabet = string.ascii_uppercase + string.digits
    for _ in range(10):
        code = ''.join(random.choice(alphabet) for _ in range(6))
        if table.get_item(Key={'game_id': code}).get('Item') is None:
            return code
    return ''.join(random.choice(alphabet) for _ in range(6))
