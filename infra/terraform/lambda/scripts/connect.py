import os
import time
import boto3

dynamodb = boto3.resource('dynamodb')
table = dynamodb.Table(os.environ['TABLE_NAME'])

def lambda_handler(event, context):
    connection_id = event['requestContext']['connectionId']
    authorizer = event.get('requestContext', {}).get('authorizer', {}) or {}
    user_id = authorizer.get('sub')
    params = event.get('queryStringParameters') or {}
    game_session_id = params.get('game_session_id')

    # Compute TTL (1 hour from now)
    ttl = int(time.time()) + 3600

    # Build the item
    item = {
        'connection_id': connection_id,
        'ttl': ttl
    }
    if game_session_id:
        item['game_session_id'] = game_session_id
    if user_id:
        item['user_id'] = user_id

    table.put_item(Item=item)

    return {
        'statusCode': 200,
        'body': 'Connected.'
    }
