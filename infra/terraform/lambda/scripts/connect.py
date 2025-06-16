import os
import time
import boto3

dynamodb = boto3.resource('dynamodb')
table = dynamodb.Table(os.environ['TABLE_NAME'])

def lambda_handler(event, context):
    connection_id = event['requestContext']['connectionId']
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

    table.put_item(Item=item)

    return {
        'statusCode': 200,
        'body': 'Connected.'
    }
