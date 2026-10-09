// Command lambda is the single Lambda that serves the REST API, the WebSocket
// API and the WebSocket authorizer of the Go backend.
package main

import (
	"context"
	"log/slog"
	"os"

	"github.com/aws/aws-lambda-go/lambda"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/pintertamas/shithead-backend/backend-go/internal/auth"
	"github.com/pintertamas/shithead-backend/backend-go/internal/handler"
	"github.com/pintertamas/shithead-backend/backend-go/internal/store"
)

func main() {
	ctx := context.Background()
	region := os.Getenv("REGION")
	if region == "" {
		region = os.Getenv("AWS_REGION")
	}
	cfg, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(region))
	if err != nil {
		slog.Error("load aws config failed", "error", err)
		os.Exit(1)
	}

	db := dynamodb.NewFromConfig(cfg)
	st := store.New(db, store.Tables{
		Games:       os.Getenv("GAME_SESSIONS_TABLE"),
		Users:       os.Getenv("USERS_TABLE"),
		Connections: os.Getenv("WS_CONNECTIONS_TABLE"),
	})
	issuer := auth.CognitoIssuer(region, os.Getenv("COGNITO_USER_POOL_ID"))
	verifier := auth.NewVerifier(issuer, os.Getenv("COGNITO_APP_CLIENT_ID"), issuer+"/.well-known/jwks.json", nil)

	h := handler.New(handler.Deps{
		Games:              st.Games,
		Users:              st.Users,
		Connections:        st.Connections,
		Notifier:           handler.NewAWSNotifier(cfg),
		Verifier:           verifier,
		ManagementEndpoint: os.Getenv("WS_MANAGEMENT_ENDPOINT"),
	})
	lambda.Start(h.Handle)
}
