package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/aws/aws-lambda-go/events"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

var errUnknownEvent = errors.New("unrecognised event shape")

// App holds the dependencies of every glue handler. Tests build it with fakes.
type App struct {
	db       Dynamo
	settings Settings
	// verifier is nil on the glue function, which never receives authorizer events.
	verifier *Verifier
	now      func() time.Time
	// newCode returns a random 6-character session code; replaced in tests.
	newCode func() (string, error)
}

// NewApp wires the handlers to their dependencies.
func NewApp(db Dynamo, settings Settings, verifier *Verifier) *App {
	return &App{
		db:       db,
		settings: settings,
		verifier: verifier,
		now:      time.Now,
		newCode:  randomSessionCode,
	}
}

// eventProbe holds just enough of an incoming event to choose a handler.
type eventProbe struct {
	TriggerSource  string `json:"triggerSource"`
	Type           string `json:"type"`
	MethodArn      string `json:"methodArn"`
	HTTPMethod     string `json:"httpMethod"`
	Source         string `json:"source"`
	DetailType     string `json:"detail-type"`
	RequestContext struct {
		RouteKey  string `json:"routeKey"`
		EventType string `json:"eventType"`
	} `json:"requestContext"`
}

// Handle dispatches one invocation by event shape:
//   - Cognito trigger (triggerSource present)     -> init user
//   - WebSocket REQUEST authorizer (type REQUEST) -> verify the ID token
//   - WebSocket route (routeKey / eventType)      -> $connect, $disconnect, $default
//   - REST proxy event (httpMethod)               -> create-game
//   - EventBridge schedule (glue function only)   -> abandoned game janitor
func (a *App) Handle(ctx context.Context, raw json.RawMessage) (any, error) {
	var probe eventProbe
	if err := json.Unmarshal(raw, &probe); err != nil {
		return nil, fmt.Errorf("decode event: %w", err)
	}
	switch {
	case probe.TriggerSource != "":
		return a.initUser(ctx, raw)
	case probe.Type == "REQUEST" && probe.MethodArn != "":
		return a.authorize(ctx, raw)
	case isScheduledEvent(probe) && a.verifier == nil:
		return a.runJanitor(ctx)
	case probe.RequestContext.RouteKey != "" || probe.RequestContext.EventType != "":
		return a.handleWebSocket(ctx, raw)
	case probe.HTTPMethod != "":
		return a.createGame(ctx, raw)
	default:
		return nil, errUnknownEvent
	}
}

// Settings are the table names the handlers read from the environment.
type Settings struct {
	GameSessionsTable string
	UsersTable        string
	ConnectionsTable  string
}

var corsHeaders = map[string]string{
	"Access-Control-Allow-Origin":  "*",
	"Access-Control-Allow-Headers": "Content-Type,Authorization",
	"Access-Control-Allow-Methods": "POST,OPTIONS",
}

// jsonResponse builds a REST proxy response with the CORS headers the browser needs.
func jsonResponse(status int, body any) (events.APIGatewayProxyResponse, error) {
	headers := map[string]string{"Content-Type": "application/json"}
	for k, v := range corsHeaders {
		headers[k] = v
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return events.APIGatewayProxyResponse{}, fmt.Errorf("encode response: %w", err)
	}
	return events.APIGatewayProxyResponse{StatusCode: status, Headers: headers, Body: string(payload)}, nil
}

// --- DynamoDB helpers -------------------------------------------------------

func s(value string) types.AttributeValue {
	return &types.AttributeValueMemberS{Value: value}
}

func key(attr, value string) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{attr: s(value)}
}

// stringAttr returns the S value of an attribute, or "" when absent or not a string.
func stringAttr(item map[string]types.AttributeValue, name string) string {
	if v, ok := item[name].(*types.AttributeValueMemberS); ok {
		return v.Value
	}
	return ""
}

// boolAttr reports whether an attribute is the BOOL value true.
func boolAttr(item map[string]types.AttributeValue, name string) bool {
	v, ok := item[name].(*types.AttributeValueMemberBOOL)
	return ok && v.Value
}

// claimsSub reads requestContext.authorizer.claims.sub from a Cognito-authorized REST event.
func claimsSub(authorizer map[string]any) string {
	claims, ok := authorizer["claims"].(map[string]any)
	if !ok {
		return ""
	}
	sub, _ := claims["sub"].(string)
	return sub
}
