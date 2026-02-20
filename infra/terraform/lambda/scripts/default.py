# default.py
import os
import json
import boto3
from boto3.dynamodb.conditions import Key

dynamodb = boto3.resource('dynamodb')
table = dynamodb.Table(os.environ['TABLE_NAME'])

def lambda_handler(event, context):
    domain = event['requestContext']['domainName']
    stage = event['requestContext']['stage']
    endpoint_url = f"https://{domain}/{stage}"
    apigw = boto3.client('apigatewaymanagementapi', endpoint_url=endpoint_url)

    # Parse incoming message
    body = json.loads(event.get('body', '{}'))
    game_session_id = body.get('game_session_id')
    message_data = body.get('data')

    if not game_session_id or message_data is None:
        return {'statusCode': 400, 'body': 'Missing game_session_id or data.'}

    # Query connections for this game session
    resp = table.query(
        IndexName='game_session_id-index',
        KeyConditionExpression=Key('game_session_id').eq(game_session_id)
    )

    # Broadcast to each connection
    for item in resp.get('Items', []):
        cid = item['connection_id']
        try:
            apigw.post_to_connection(ConnectionId=cid, Data=message_data)
        except apigw.exceptions.GoneException:
            # Remove stale connections
            table.delete_item(Key={'connection_id': cid})

    return {'statusCode': 200, 'body': 'Message broadcast.'}
