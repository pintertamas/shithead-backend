import json
import boto3
import uuid
import os
from datetime import datetime
from boto3.dynamodb.conditions import Key, Attr

ecs        = boto3.client('ecs')
dynamodb   = boto3.resource('dynamodb')

def lambda_handler(event, context):
    try:
        claims  = event['requestContext']['authorizer']['claims']
        user_id = claims['sub']
    except KeyError:
        user_id = "anonymous"

    cluster_name        = os.environ['ECS_CLUSTER_NAME']
    task_definition     = os.environ['TASK_DEFINITION']
    subnets             = os.environ['SUBNETS'].split(',')
    container_name      = os.environ['CONTAINER_NAME']
    idle_timeout        = os.environ['IDLE_TIMEOUT_MINUTES']
    aws_region          = os.environ['AWS_REGION']
    sessions_table_name = os.environ['GAME_SESSIONS_TABLE']
    users_table_name    = os.environ['USERS_TABLE']

    sessions_table = dynamodb.Table(sessions_table_name)

    user_sessions = sessions_table.query(
        IndexName='user_id-index',  # GSI on user_id
        KeyConditionExpression=Key('user_id').eq(user_id),
        FilterExpression=Attr('status').is_in(['STARTING', 'RUNNING']) & Attr('ended_at').not_exists()
    )['Items']

    if user_sessions:
        # User already has a running game
        response_message = (
            "You already have an active game session (ID: {}). "
            "Please finish or end that session before creating a new one."
        ).format(user_sessions[0]['game_id'])
        new_game_id = user_sessions[0]['game_id']

    else:
        existing_tasks = ecs.list_tasks(
            cluster=cluster_name,
            family=task_definition.split(':')[0]
        )['taskArns']

        global_active = sessions_table.scan(
            FilterExpression=Attr('status').is_in(['STARTING', 'RUNNING']) & Attr('ended_at').not_exists()
        )['Items']

        if existing_tasks or global_active:
            response_message = (
                "An ECS task is already running or pending for another session; "
                "no new task will be started at this time."
            )
            new_game_id = global_active[0]['game_id'] if global_active else None

        else:
            new_game_id = str(uuid.uuid4())
            ecs.run_task(
                cluster=cluster_name,
                launchType='FARGATE',
                taskDefinition=task_definition,
                count=1,
                networkConfiguration={
                    'awsvpcConfiguration': {
                        'subnets': subnets,
                        'assignPublicIp': 'ENABLED'
                    }
                },
                overrides={
                    'containerOverrides': [{
                        'name': container_name,
                        'environment': [
                            {'name': 'GAME_SESSION_ID',   'value': new_game_id},
                            {'name': 'USER_ID',           'value': user_id},
                            {'name': 'IDLE_TIMEOUT_MINUTES','value': idle_timeout},
                            {'name': 'AWS_REGION',        'value': aws_region},
                            {'name': 'GAME_SESSIONS_TABLE','value': sessions_table_name},
                            {'name': 'USERS_TABLE',       'value': users_table_name},
                        ]
                    }]
                }
            )
            response_message = 'New ECS task started successfully.'

    sessions_table.put_item(Item={
        'game_id':    new_game_id,
        'user_id':    user_id,
        'status':     'STARTING',
        'created_at': datetime.utcnow().isoformat()
    })

    return {
        'statusCode': 200,
        'body': json.dumps({
            'gameSessionId': new_game_id,
            'message':       response_message
        })
    }
