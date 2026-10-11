package main

import (
	"context"
	"testing"

	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
)

func playerMap(id string, extra ...string) types.AttributeValue {
	m := map[string]types.AttributeValue{"playerId": s(id), "username": s(id)}
	if len(extra) > 0 {
		m["botType"] = s(extra[0])
	}
	return &types.AttributeValueMemberM{Value: m}
}

func ownedLobby(id, owner string, players ...types.AttributeValue) map[string]types.AttributeValue {
	return map[string]types.AttributeValue{
		"game_id": s(id),
		"user_id": s(owner),
		"started": boolValue(false),
		"players": &types.AttributeValueMemberL{Value: players},
	}
}

func TestCleanupHandsLobbyToFirstHuman(t *testing.T) {
	db := newFakeDynamo()
	db.seed(testGames, ownedLobby("L1", "owner", playerMap("owner"), playerMap("bot-1", "BEGINNER"), playerMap("human")))
	app := newTestApp(db)

	if err := app.cleanupOldSessions(context.Background(), "owner"); err != nil {
		t.Fatal(err)
	}
	item := db.item(testGames, "L1")
	if item == nil {
		t.Fatal("lobby was deleted")
	}
	if got := stringAttr(item, "user_id"); got != "human" {
		t.Fatalf("owner = %q, want human", got)
	}
	players := item["players"].(*types.AttributeValueMemberL).Value
	if len(players) != 2 || playerIDOf(players[0]) != "bot-1" || playerIDOf(players[1]) != "human" {
		t.Fatalf("players = %v, want bot kept", players)
	}
}

func TestCleanupDeletesLobbyWithOnlyBotsLeft(t *testing.T) {
	db := newFakeDynamo()
	db.seed(testGames, ownedLobby("L2", "owner", playerMap("owner"), playerMap("bot-1", "BEGINNER")))
	app := newTestApp(db)

	if err := app.cleanupOldSessions(context.Background(), "owner"); err != nil {
		t.Fatal(err)
	}
	if db.item(testGames, "L2") != nil {
		t.Fatal("lobby with only bots left should be deleted")
	}
}

func TestIsBotPlayerTreatsNullAndEmptyAsHuman(t *testing.T) {
	null := &types.AttributeValueMemberM{Value: map[string]types.AttributeValue{
		"playerId": s("p"), "botType": &types.AttributeValueMemberNULL{Value: true},
	}}
	cases := map[string]struct {
		player types.AttributeValue
		want   bool
	}{
		"bot":     {playerMap("bot-1", "BEGINNER"), true},
		"human":   {playerMap("h"), false},
		"empty":   {playerMap("h", ""), false},
		"null":    {null, false},
		"not map": {s("x"), false},
	}
	for name, c := range cases {
		if got := isBotPlayer(c.player); got != c.want {
			t.Errorf("%s: isBotPlayer = %v, want %v", name, got, c.want)
		}
	}
}
