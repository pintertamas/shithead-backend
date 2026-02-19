import os
import json
import requests
import logging
from jose import jwt
from jose.exceptions import JWSError, JWTError

logger = logging.getLogger()
logger.setLevel(logging.INFO)

POOL_ID   = os.environ["COGNITO_USER_POOL_ID"]
CLIENT_ID = os.environ["COGNITO_APP_CLIENT_ID"]
REGION    = os.environ["REGION"]
ISSUER    = f"https://cognito-idp.{REGION}.amazonaws.com/{POOL_ID}"

_jwks_keys = requests.get(f"{ISSUER}/.well-known/jwks.json").json().get("keys", [])

def lambda_handler(event, context):
    # Normalize headers
    headers = {k.lower(): v for k, v in event.get("headers", {}).items()}
    raw_proto_or_auth = headers.get("sec-websocket-protocol") or headers.get("authorization")
    if not raw_proto_or_auth:
        logger.warning("No Bearer token provided")
        raise Exception("Unauthorized")

    parts = raw_proto_or_auth.split(None, 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        logger.warning("Malformed auth header: %s", raw_proto_or_auth)
        raise Exception("Unauthorized")

    token = parts[1]
    try:
        unverified = jwt.get_unverified_header(token)
    except JWSError as e:
        logger.warning("Invalid JWT header: %s", e)
        raise Exception("Unauthorized")

    kid = unverified.get("kid")
    key = next((j for j in _jwks_keys if j["kid"] == kid), None)
    if not key:
        logger.warning("No matching JWK for kid: %s", kid)
        raise Exception("Unauthorized")

    try:
        claims = jwt.decode(
            token, key,
            algorithms=["RS256"],
            audience=CLIENT_ID,
            issuer=ISSUER,
            options={"verify_at_hash": False}
        )
    except JWTError as e:
        logger.error("JWT validation failed: %s", e, exc_info=False)
        raise Exception("Unauthorized")

    proto_header = headers.get("sec-websocket-protocol")

    return {
        "isAuthorized": True,
        "principalId": claims.get("sub"),
        "websocketResponse": {
            "headers": {
                "Sec-WebSocket-Protocol": proto_header
            }
        },
        "context": {
            "username": claims.get("cognito:username"),
            "sub":       claims.get("sub")
        }
    }
