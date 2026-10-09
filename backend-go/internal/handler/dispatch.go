package handler

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"strings"

	"github.com/aws/aws-lambda-go/events"
)

// ErrUnsupportedEvent is returned for payloads that match no known event source.
var ErrUnsupportedEvent = errors.New("unsupported event")

// envelope holds the fields used to tell the event sources apart.
type envelope struct {
	MethodARN      string `json:"methodArn"`
	HTTPMethod     string `json:"httpMethod"`
	RequestContext struct {
		RouteKey string `json:"routeKey"`
	} `json:"requestContext"`
}

// Handle is the Lambda entry point. The event shape selects the route: a
// methodArn means the WebSocket authorizer, a routeKey means a WebSocket route,
// and an httpMethod means a REST proxy request.
func (h *Handler) Handle(ctx context.Context, raw json.RawMessage) (any, error) {
	var env envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, err
	}
	switch {
	case env.MethodARN != "":
		var req events.APIGatewayCustomAuthorizerRequestTypeRequest
		if err := json.Unmarshal(raw, &req); err != nil {
			return nil, err
		}
		return h.authorize(ctx, req)
	case env.RequestContext.RouteKey != "":
		var req events.APIGatewayWebsocketProxyRequest
		if err := json.Unmarshal(raw, &req); err != nil {
			return nil, err
		}
		return h.websocket(ctx, req), nil
	case env.HTTPMethod != "":
		var req events.APIGatewayProxyRequest
		if err := json.Unmarshal(raw, &req); err != nil {
			return nil, err
		}
		return h.rest(ctx, req), nil
	default:
		return nil, ErrUnsupportedEvent
	}
}

// websocket routes a WebSocket message by its route key.
func (h *Handler) websocket(ctx context.Context, req events.APIGatewayWebsocketProxyRequest) events.APIGatewayProxyResponse {
	switch req.RequestContext.RouteKey {
	case "$connect":
		return h.connect(ctx, req)
	case "$disconnect":
		return h.disconnect(ctx, req)
	case "$default":
		return statusOnly(http.StatusOK)
	case "play":
		return h.play(ctx, req)
	case "setup":
		return h.setup(ctx, req)
	case "pickup":
		return h.pickup(ctx, req)
	default:
		return statusOnly(http.StatusBadRequest)
	}
}

// authorize checks the Cognito ID token passed as ?token= (or a bearer header).
func (h *Handler) authorize(ctx context.Context, req events.APIGatewayCustomAuthorizerRequestTypeRequest) (events.APIGatewayCustomAuthorizerResponse, error) {
	token := tokenFromRequest(req.QueryStringParameters, req.Headers)
	if token == "" {
		return events.APIGatewayCustomAuthorizerResponse{}, errUnauthorized
	}
	claims, err := h.d.Verifier.Verify(ctx, token)
	if err != nil {
		slog.Warn("websocket token rejected", "error", err)
		return events.APIGatewayCustomAuthorizerResponse{}, errUnauthorized
	}
	sub := claims.Subject()
	authCtx := map[string]any{"sub": sub}
	if name, ok := claims["cognito:username"].(string); ok && name != "" {
		authCtx["username"] = name
	}
	return events.APIGatewayCustomAuthorizerResponse{
		PrincipalID: sub,
		PolicyDocument: events.APIGatewayCustomAuthorizerPolicy{
			Version: "2012-10-17",
			Statement: []events.IAMPolicyStatement{{
				Action:   []string{"execute-api:Invoke"},
				Effect:   "Allow",
				Resource: []string{req.MethodArn},
			}},
		},
		Context: authCtx,
	}, nil
}

// errUnauthorized is the message API Gateway maps to a 401 for REQUEST authorizers.
var errUnauthorized = errors.New("Unauthorized")

func tokenFromRequest(query, headers map[string]string) string {
	if token := query["token"]; token != "" {
		return token
	}
	for _, name := range []string{"sec-websocket-protocol", "authorization"} {
		fields := strings.Fields(headerValue(headers, name))
		if len(fields) == 2 && strings.EqualFold(fields[0], "bearer") {
			return fields[1]
		}
	}
	return ""
}

func headerValue(headers map[string]string, name string) string {
	for k, v := range headers {
		if strings.EqualFold(k, name) {
			return v
		}
	}
	return ""
}

func endpointOf(req events.APIGatewayWebsocketProxyRequest) string {
	return "https://" + req.RequestContext.DomainName + "/" + req.RequestContext.Stage
}

func stringFrom(m map[string]any, key string) string {
	value, _ := m[key].(string)
	return value
}

// defaultGameIDAlphabet is the character set of session codes.
const defaultGameIDAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

func randomGameID() string {
	b := make([]byte, 6)
	for i := range b {
		b[i] = defaultGameIDAlphabet[rand.IntN(len(defaultGameIDAlphabet))]
	}
	return string(b)
}
