package store

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/aws/aws-sdk-go-v2/feature/dynamodb/attributevalue"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb/types"
	"golang.org/x/text/unicode/norm"
)

const (
	// LeaderboardPartition is the leaderboard_pk value of every ranked profile.
	LeaderboardPartition = "global"
	// DefaultElo is the rating given to a new profile.
	DefaultElo = 1000

	claimPrefix     = "__username__#"
	claimOwnerAttr  = "owner_user_id"
	batchGetMaxKeys = 100
	batchGetRetries = 5
)

// Users is the repository for the users table (key user_id). Nickname claim
// rows (user_id "__username__#<name>") live in the same table.
type Users struct {
	db    DynamoDB
	table string
}

// Get returns the profile, or nil when it does not exist.
func (u *Users) Get(ctx context.Context, userID string) (*UserRecord, error) {
	out, err := u.db.GetItem(ctx, &dynamodb.GetItemInput{
		TableName:      &u.table,
		Key:            strKey("user_id", userID),
		ConsistentRead: boolPtr(true),
	})
	if err != nil {
		return nil, fmt.Errorf("get user %s: %w", userID, err)
	}
	if len(out.Item) == 0 {
		return nil, nil
	}
	return decodeUser(out.Item)
}

// EnsureProfile is the lazy get-or-create: missing profiles get the default
// rating and leaderboard partition; existing values are never overwritten.
func (u *Users) EnsureProfile(ctx context.Context, userID string) (*UserRecord, error) {
	out, err := u.db.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName:        &u.table,
		Key:              strKey("user_id", userID),
		UpdateExpression: strPtr("SET elo_score = if_not_exists(elo_score, :elo), leaderboard_pk = if_not_exists(leaderboard_pk, :lpk)"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":elo": &types.AttributeValueMemberN{Value: fmt.Sprint(DefaultElo)},
			":lpk": &types.AttributeValueMemberS{Value: LeaderboardPartition},
		},
		ReturnValues: types.ReturnValueAllNew,
	})
	if err != nil {
		return nil, fmt.Errorf("ensure profile %s: %w", userID, err)
	}
	return decodeUser(out.Attributes)
}

// BatchGet loads the profiles that exist among userIDs.
func (u *Users) BatchGet(ctx context.Context, userIDs []string) ([]UserRecord, error) {
	unique := dedupe(userIDs)
	var users []UserRecord
	for start := 0; start < len(unique); start += batchGetMaxKeys {
		end := min(start+batchGetMaxKeys, len(unique))
		batch, err := u.batchGetChunk(ctx, unique[start:end])
		if err != nil {
			return nil, err
		}
		users = append(users, batch...)
	}
	return users, nil
}

func (u *Users) batchGetChunk(ctx context.Context, ids []string) ([]UserRecord, error) {
	keys := make([]map[string]types.AttributeValue, 0, len(ids))
	for _, id := range ids {
		keys = append(keys, strKey("user_id", id))
	}
	pending := map[string]types.KeysAndAttributes{u.table: {Keys: keys, ConsistentRead: boolPtr(true)}}
	var users []UserRecord
	for attempt := 0; len(pending) > 0 && attempt < batchGetRetries; attempt++ {
		out, err := u.db.BatchGetItem(ctx, &dynamodb.BatchGetItemInput{RequestItems: pending})
		if err != nil {
			return nil, fmt.Errorf("batch get users: %w", err)
		}
		for _, item := range out.Responses[u.table] {
			user, err := decodeUser(item)
			if err != nil {
				return nil, err
			}
			users = append(users, *user)
		}
		pending = out.UnprocessedKeys
	}
	return users, nil
}

// SetElo stores a new rating. It reports false when the profile is missing.
func (u *Users) SetElo(ctx context.Context, userID string, elo float64) (bool, error) {
	_, err := u.db.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName:           &u.table,
		Key:                 strKey("user_id", userID),
		UpdateExpression:    strPtr("SET elo_score = :elo"),
		ConditionExpression: strPtr("attribute_exists(user_id)"),
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":elo": &types.AttributeValueMemberN{Value: fmt.Sprint(elo)},
		},
	})
	var failed *types.ConditionalCheckFailedException
	if errors.As(err, &failed) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("set elo for %s: %w", userID, err)
	}
	return true, nil
}

// TopByElo returns the highest rated profiles, best first.
func (u *Users) TopByElo(ctx context.Context, limit int) ([]UserRecord, error) {
	out, err := u.db.Query(ctx, &dynamodb.QueryInput{
		TableName:                 &u.table,
		IndexName:                 strPtr("leaderboard-index"),
		KeyConditionExpression:    strPtr("leaderboard_pk = :pk"),
		ExpressionAttributeValues: map[string]types.AttributeValue{":pk": &types.AttributeValueMemberS{Value: LeaderboardPartition}},
		ScanIndexForward:          boolPtr(false),
		Limit:                     int32Ptr(int32(limit)),
	})
	if err != nil {
		return nil, fmt.Errorf("query leaderboard: %w", err)
	}
	users := make([]UserRecord, 0, len(out.Items))
	for _, item := range out.Items {
		user, err := decodeUser(item)
		if err != nil {
			return nil, err
		}
		users = append(users, *user)
	}
	return users, nil
}

// ReserveUsername sets a nickname if no other profile uses it (case and
// whitespace insensitive). The nickname claim row is moved in the same
// transaction so two profiles can never race for the same name. On success the
// profile's Username is updated.
func (u *Users) ReserveUsername(ctx context.Context, profile *UserRecord, username string) (bool, error) {
	normalized := NormalizeUsername(username)
	var current *string
	if profile.Username != "" {
		c := NormalizeUsername(profile.Username)
		current = &c
	}
	if current != nil && *current == normalized {
		if err := u.setUsername(ctx, profile.UserID, username); err != nil {
			return false, err
		}
		profile.Username = username
		return true, nil
	}
	taken, err := u.usernameInUse(ctx, normalized, profile.UserID)
	if err != nil || taken {
		return false, err
	}
	err = u.transferClaim(ctx, profile, username, normalized, current)
	if err == nil {
		profile.Username = username
		return true, nil
	}
	var canceled *types.TransactionCanceledException
	if errors.As(err, &canceled) {
		return u.resolveCancelled(ctx, profile, normalized, err)
	}
	return false, err
}

func (u *Users) setUsername(ctx context.Context, userID, username string) error {
	_, err := u.db.UpdateItem(ctx, &dynamodb.UpdateItemInput{
		TableName:                &u.table,
		Key:                      strKey("user_id", userID),
		UpdateExpression:         strPtr("SET #username = :name"),
		ExpressionAttributeNames: map[string]string{"#username": "username"},
		ExpressionAttributeValues: map[string]types.AttributeValue{
			":name": &types.AttributeValueMemberS{Value: username},
		},
	})
	if err != nil {
		return fmt.Errorf("set username for %s: %w", userID, err)
	}
	return nil
}

// transferClaim writes the new claim, renames the profile and releases the old
// claim atomically. The old claim is released only when this user owns it.
func (u *Users) transferClaim(ctx context.Context, profile *UserRecord, username, normalized string, current *string) error {
	newClaim := claimID(normalized)
	items := []types.TransactWriteItem{
		{Put: u.claimPut(newClaim, profile.UserID)},
		{Update: u.usernameUpdate(profile, username, current)},
	}
	if current != nil && claimID(*current) != newClaim {
		oldClaim := claimID(*current)
		owner, err := u.claimOwner(ctx, oldClaim)
		if err != nil {
			return err
		}
		if owner == profile.UserID {
			items = append(items, types.TransactWriteItem{Delete: u.claimDelete(oldClaim, profile.UserID)})
		}
	}
	_, err := u.db.TransactWriteItems(ctx, &dynamodb.TransactWriteItemsInput{TransactItems: items})
	return err
}

func (u *Users) claimPut(claim, userID string) *types.Put {
	return &types.Put{
		TableName: &u.table,
		Item: map[string]types.AttributeValue{
			"user_id":      &types.AttributeValueMemberS{Value: claim},
			claimOwnerAttr: &types.AttributeValueMemberS{Value: userID},
		},
		ConditionExpression:       strPtr("attribute_not_exists(user_id) OR #owner = :owner"),
		ExpressionAttributeNames:  map[string]string{"#owner": claimOwnerAttr},
		ExpressionAttributeValues: map[string]types.AttributeValue{":owner": &types.AttributeValueMemberS{Value: userID}},
	}
}

func (u *Users) usernameUpdate(profile *UserRecord, username string, current *string) *types.Update {
	values := map[string]types.AttributeValue{":newUsername": &types.AttributeValueMemberS{Value: username}}
	condition := "attribute_not_exists(#username)"
	if current != nil {
		condition = "#username = :currentUsername"
		values[":currentUsername"] = &types.AttributeValueMemberS{Value: profile.Username}
	}
	return &types.Update{
		TableName:                 &u.table,
		Key:                       strKey("user_id", profile.UserID),
		UpdateExpression:          strPtr("SET #username = :newUsername"),
		ConditionExpression:       &condition,
		ExpressionAttributeNames:  map[string]string{"#username": "username"},
		ExpressionAttributeValues: values,
	}
}

func (u *Users) claimDelete(claim, userID string) *types.Delete {
	return &types.Delete{
		TableName:                 &u.table,
		Key:                       strKey("user_id", claim),
		ConditionExpression:       strPtr("#owner = :owner"),
		ExpressionAttributeNames:  map[string]string{"#owner": claimOwnerAttr},
		ExpressionAttributeValues: map[string]types.AttributeValue{":owner": &types.AttributeValueMemberS{Value: userID}},
	}
}

// resolveCancelled decides what a cancelled nickname transaction means: another
// user won the race (false), or the profile already carries this nickname (true).
func (u *Users) resolveCancelled(ctx context.Context, profile *UserRecord, normalized string, cause error) (bool, error) {
	owner, err := u.claimOwner(ctx, claimID(normalized))
	if err != nil {
		return false, err
	}
	taken, err := u.usernameInUse(ctx, normalized, profile.UserID)
	if err != nil {
		return false, err
	}
	if taken || (owner != "" && owner != profile.UserID) {
		return false, nil
	}
	latest, err := u.Get(ctx, profile.UserID)
	if err != nil {
		return false, err
	}
	if latest != nil && latest.Username != "" && NormalizeUsername(latest.Username) == normalized {
		profile.Username = latest.Username
		return true, nil
	}
	return false, cause
}

// usernameInUse scans the table for another profile with the same nickname.
func (u *Users) usernameInUse(ctx context.Context, normalized, excludingUserID string) (bool, error) {
	var startKey map[string]types.AttributeValue
	for {
		out, err := u.db.Scan(ctx, &dynamodb.ScanInput{
			TableName:                &u.table,
			ConsistentRead:           boolPtr(true),
			ProjectionExpression:     strPtr("#id, #name"),
			ExpressionAttributeNames: map[string]string{"#id": "user_id", "#name": "username"},
			ExclusiveStartKey:        startKey,
		})
		if err != nil {
			return false, fmt.Errorf("scan usernames: %w", err)
		}
		for _, item := range out.Items {
			id := stringAttr(item, "user_id")
			name := stringAttr(item, "username")
			if id != "" && id != excludingUserID && name != "" && NormalizeUsername(name) == normalized {
				return true, nil
			}
		}
		if len(out.LastEvaluatedKey) == 0 {
			return false, nil
		}
		startKey = out.LastEvaluatedKey
	}
}

func (u *Users) claimOwner(ctx context.Context, claim string) (string, error) {
	out, err := u.db.GetItem(ctx, &dynamodb.GetItemInput{
		TableName:      &u.table,
		Key:            strKey("user_id", claim),
		ConsistentRead: boolPtr(true),
	})
	if err != nil {
		return "", fmt.Errorf("read nickname claim: %w", err)
	}
	return stringAttr(out.Item, claimOwnerAttr), nil
}

// NormalizeUsername is the comparison form of a nickname: NFKC, trimmed,
// single-spaced and lower-cased.
func NormalizeUsername(name string) string {
	text := norm.NFKC.String(strings.TrimSpace(name))
	return strings.ToLower(strings.Join(strings.Fields(text), " "))
}

func claimID(normalized string) string {
	return claimPrefix + normalized
}

func decodeUser(item map[string]types.AttributeValue) (*UserRecord, error) {
	var user UserRecord
	if err := attributevalue.UnmarshalMap(item, &user); err != nil {
		return nil, fmt.Errorf("decode user: %w", err)
	}
	return &user, nil
}

func dedupe(ids []string) []string {
	seen := make(map[string]bool, len(ids))
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

func int32Ptr(v int32) *int32 { return &v }
