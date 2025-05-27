import json
import boto3
import uuid
import os

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

    # Start ECS task
    response = ecs.run_task(
        cluster=os.environ['ECS_CLUSTER_NAME'],
        launchType='FARGATE',
        taskDefinition=os.environ['TASK_DEFINITION'],
        count=1,
        networkConfiguration={
            'awsvpcConfiguration': {
                'subnets': os.environ['SUBNETS'].split(','),
                'assignPublicIp': 'ENABLED'
            }
        },
        overrides={
            'containerOverrides': [
                {
                    'name': os.environ['CONTAINER_NAME'],
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
        'gameSessionId': game_session_id,
        'userId': user_id,
        'status': 'STARTING'
    })

    return {
        'statusCode': 200,
        'body': json.dumps({'gameSessionId': game_session_id})
    }
