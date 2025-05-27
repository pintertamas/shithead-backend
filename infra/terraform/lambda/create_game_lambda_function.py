import json
import boto3
import uuid
import os
from datetime import datetime

ecs = boto3.client('ecs')
dynamodb = boto3.resource('dynamodb')

def lambda_handler(event, context):
    try:
        # Extract user ID from Cognito claims
        claims = event['requestContext']['authorizer']['claims']
        user_id = claims['sub']
    except KeyError:
        # For local or test-invoke
        user_id = "anonymous"

    # Generate a unique game session ID
    game_session_id = str(uuid.uuid4())

    cluster_name = os.environ['ECS_CLUSTER_NAME']
    task_definition = os.environ['TASK_DEFINITION']
    subnets = os.environ['SUBNETS']
    container_name = os.environ['CONTAINER_NAME']

    response = ecs.run_task(
        cluster = cluster_name,
        launchType='FARGATE',
        taskDefinition = task_definition,
        count=1,
        networkConfiguration={
            'awsvpcConfiguration': {
                'subnets': subnets.split(','),
                'assignPublicIp': 'ENABLED'
            }
        },
        overrides={
            'containerOverrides': [
                {
                    'name': container_name,
                    'environment': [
                        {'name': 'GAME_SESSION_ID', 'value': game_session_id},
                        {'name': 'USER_ID', 'value': user_id}
                    ]
                }
            ]
        }
    )

    # Store game session in DynamoDB
    table = dynamodb.Table(os.environ['DYNAMODB_TABLE'])
    table.put_item(Item={
        'game_id': game_session_id,
        'user_id': user_id,
        'status': 'STARTING',
        'created_at': datetime.now().isoformat()
    })

    return {
        'statusCode': 200,
        'body': json.dumps({'gameSessionId': game_session_id})
    }
