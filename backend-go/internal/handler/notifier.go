package handler

import (
	"context"
	"errors"
	"sync"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/apigatewaymanagementapi"
	gwtypes "github.com/aws/aws-sdk-go-v2/service/apigatewaymanagementapi/types"
)

// AWSNotifier sends data through the API Gateway management API. One client is
// kept per endpoint (domain and stage) so that connections are reused.
type AWSNotifier struct {
	cfg     aws.Config
	mu      sync.Mutex
	clients map[string]*apigatewaymanagementapi.Client
}

// NewAWSNotifier creates a notifier that uses the given AWS configuration.
func NewAWSNotifier(cfg aws.Config) *AWSNotifier {
	return &AWSNotifier{cfg: cfg, clients: map[string]*apigatewaymanagementapi.Client{}}
}

func (n *AWSNotifier) client(endpoint string) *apigatewaymanagementapi.Client {
	n.mu.Lock()
	defer n.mu.Unlock()
	if c, ok := n.clients[endpoint]; ok {
		return c
	}
	c := apigatewaymanagementapi.NewFromConfig(n.cfg, func(o *apigatewaymanagementapi.Options) {
		o.BaseEndpoint = aws.String(endpoint)
	})
	n.clients[endpoint] = c
	return c
}

// Post sends data to a connection. A GoneException becomes ErrConnectionGone.
func (n *AWSNotifier) Post(ctx context.Context, endpoint, connectionID string, data []byte) error {
	_, err := n.client(endpoint).PostToConnection(ctx, &apigatewaymanagementapi.PostToConnectionInput{
		ConnectionId: aws.String(connectionID),
		Data:         data,
	})
	return translateGone(err)
}

// Delete closes a connection from the server side.
func (n *AWSNotifier) Delete(ctx context.Context, endpoint, connectionID string) error {
	_, err := n.client(endpoint).DeleteConnection(ctx, &apigatewaymanagementapi.DeleteConnectionInput{
		ConnectionId: aws.String(connectionID),
	})
	return translateGone(err)
}

func translateGone(err error) error {
	var gone *gwtypes.GoneException
	if errors.As(err, &gone) {
		return ErrConnectionGone
	}
	return err
}
