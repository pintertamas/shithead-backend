package auth

import (
	"strings"
)

// AdminGroup is the Cognito group that may run the game-wide cleanup.
const AdminGroup = "game-admin"

// Subject returns the Cognito user id (sub) or "" when absent.
func (c Claims) Subject() string {
	return stringClaim(c, "sub")
}

// Username returns a display-name hint for a new profile, following the same
// precedence as the Java service: preferred_username, cognito:username, email.
// It returns "" when none is present.
func (c Claims) Username() string {
	for _, key := range []string{"preferred_username", "cognito:username", "email"} {
		if name := stringClaim(c, key); name != "" {
			return name
		}
	}
	return ""
}

// IsGameAdmin reports whether cognito:groups contains the admin group. The claim
// may arrive as a JSON array or as the bracketed text form "[a, b]".
func (c Claims) IsGameAdmin() bool {
	switch groups := c["cognito:groups"].(type) {
	case []any:
		for _, g := range groups {
			if name, ok := g.(string); ok && name == AdminGroup {
				return true
			}
		}
	case string:
		normalized := strings.NewReplacer("[", "", "]", "").Replace(groups)
		for _, part := range strings.Split(normalized, ",") {
			if strings.TrimSpace(part) == AdminGroup {
				return true
			}
		}
	}
	return false
}

// FromAuthorizer extracts the claims map that API Gateway's Cognito authorizer
// places under requestContext.authorizer.claims. It returns nil when absent.
func FromAuthorizer(authorizer map[string]any) Claims {
	raw, ok := authorizer["claims"].(map[string]any)
	if !ok {
		return nil
	}
	return Claims(raw)
}

func stringClaim(c Claims, key string) string {
	value, _ := c[key].(string)
	return value
}
