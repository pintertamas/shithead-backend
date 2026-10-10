package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	v4 "github.com/aws/aws-sdk-go-v2/aws/signer/v4"
	"github.com/aws/aws-sdk-go-v2/config"
)

// connectionTimeout bounds one GetConnection call. A call that times out is
// unknown, so the janitor keeps the game.
const connectionTimeout = 3 * time.Second

// emptyPayloadSHA256 is the SHA-256 of an empty body, which GetConnection sends.
const emptyPayloadSHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

// connectionState is what API Gateway reports for a WebSocket connection.
type connectionState int

const (
	connectionOpen connectionState = iota + 1
	connectionGone
)

// connectionChecker asks API Gateway whether a WebSocket connection still exists.
// An error means the answer is unknown, and the caller must keep the game.
type connectionChecker interface {
	check(ctx context.Context, connectionID string) (connectionState, error)
}

// newConnectionChecker builds the checker from the Lambda environment: the WebSocket
// management endpoint in WS_MANAGEMENT_ENDPOINT (the same value the game API uses)
// and the function's AWS credentials and region. When that cannot be set up, the
// checker fails every call, so games with connection rows that look live are kept.
func newConnectionChecker(ctx context.Context) connectionChecker {
	endpoint := os.Getenv("WS_MANAGEMENT_ENDPOINT")
	if endpoint == "" {
		return failingChecker{err: errors.New("WS_MANAGEMENT_ENDPOINT is not set")}
	}
	cfg, err := config.LoadDefaultConfig(ctx)
	if err != nil {
		return failingChecker{err: fmt.Errorf("load AWS config: %w", err)}
	}
	if cfg.Credentials == nil || cfg.Region == "" {
		return failingChecker{err: errors.New("AWS credentials or region are not configured")}
	}
	return managementAPIChecker{
		endpoint:    strings.TrimSuffix(endpoint, "/"),
		region:      cfg.Region,
		credentials: cfg.Credentials,
		signer:      v4.NewSigner(),
		client:      &http.Client{Timeout: connectionTimeout},
	}
}

// managementAPIChecker calls GET {endpoint}/@connections/{id} with a SigV4 signature
// for the execute-api service. The glue role grants execute-api:ManageConnections.
type managementAPIChecker struct {
	endpoint    string
	region      string
	credentials aws.CredentialsProvider
	signer      *v4.Signer
	client      *http.Client
}

// check maps HTTP 200 to open and HTTP 410 to gone. Any other status, transport
// failure or missing id is an error, which the janitor treats as live.
func (c managementAPIChecker) check(ctx context.Context, connectionID string) (connectionState, error) {
	if connectionID == "" {
		return 0, errors.New("connection id is empty")
	}
	target := c.endpoint + "/@connections/" + url.PathEscape(connectionID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return 0, fmt.Errorf("build GetConnection request: %w", err)
	}
	creds, err := c.credentials.Retrieve(ctx)
	if err != nil {
		return 0, fmt.Errorf("retrieve credentials: %w", err)
	}
	if err := c.signer.SignHTTP(ctx, creds, req, emptyPayloadSHA256, "execute-api", c.region, time.Now()); err != nil {
		return 0, fmt.Errorf("sign GetConnection request: %w", err)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return 0, fmt.Errorf("GetConnection: %w", err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4<<10))
	switch resp.StatusCode {
	case http.StatusOK:
		return connectionOpen, nil
	case http.StatusGone:
		return connectionGone, nil
	default:
		return 0, fmt.Errorf("GetConnection returned HTTP %d", resp.StatusCode)
	}
}

// failingChecker is used when the checker cannot be built. Every check reports err.
type failingChecker struct {
	err error
}

func (c failingChecker) check(context.Context, string) (connectionState, error) {
	return 0, c.err
}
