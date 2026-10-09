// Command glue is the single Go binary behind the small Lambda functions that
// the game does not need Java for: REST create-game, WebSocket connect /
// disconnect / default, the WebSocket REQUEST authorizer and the Cognito
// post-confirmation / post-authentication trigger.
//
// The same bootstrap is deployed as two Lambda functions. The authorizer
// function additionally receives the Cognito pool and app client ids.
package main

import (
	"context"
	"log"
	"os"

	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
)

func main() {
	awsCfg, err := config.LoadDefaultConfig(context.Background())
	if err != nil {
		log.Fatalf("load AWS config: %v", err)
	}
	app := NewApp(dynamodb.NewFromConfig(awsCfg), settingsFromEnv(), verifierFromEnv())
	lambda.Start(app.Handle)
}

func settingsFromEnv() Settings {
	return Settings{
		GameSessionsTable: os.Getenv("GAME_SESSIONS_TABLE"),
		UsersTable:        os.Getenv("USERS_TABLE"),
		ConnectionsTable:  os.Getenv("WS_CONNECTIONS_TABLE"),
	}
}

// verifierFromEnv returns a Cognito ID token verifier when the pool and app
// client ids are configured (the authorizer function); otherwise nil.
func verifierFromEnv() *Verifier {
	poolID := os.Getenv("COGNITO_USER_POOL_ID")
	clientID := os.Getenv("COGNITO_APP_CLIENT_ID")
	if poolID == "" || clientID == "" {
		return nil
	}
	return NewVerifier(regionFromEnv(), poolID, clientID)
}

func regionFromEnv() string {
	if region := os.Getenv("REGION"); region != "" {
		return region
	}
	return os.Getenv("AWS_REGION")
}
