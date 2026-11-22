-- Owned by the auth module. One schema per module: nothing outside core/auth touches these tables.

CREATE TABLE auth.users (
  id            uuid PRIMARY KEY,
  email         text NOT NULL,
  password_hash text NOT NULL,
  display_name  text NOT NULL,
  member_tier   text NOT NULL DEFAULT 'STANDARD'
    CONSTRAINT users_member_tier_check CHECK (member_tier IN ('STANDARD', 'SILVER', 'GOLD', 'PLATINUM')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Emails are unique regardless of case: Linh@Example.com and linh@example.com are one account.
CREATE UNIQUE INDEX users_email_lower_key ON auth.users (lower(email));

COMMENT ON COLUMN auth.users.password_hash IS 'BCrypt hash (cost 12), never a plain password.';

-- One row per login.
CREATE TABLE auth.sessions (
  id         uuid PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz
);

CREATE INDEX sessions_user_id_idx ON auth.sessions (user_id);

-- Refresh tokens belong to a session (the token family).
CREATE TABLE auth.refresh_tokens (
  id         uuid PRIMARY KEY,
  session_id uuid NOT NULL REFERENCES auth.sessions (id) ON DELETE CASCADE,
  token_hash char(64) NOT NULL CONSTRAINT refresh_tokens_token_hash_key UNIQUE
    CONSTRAINT refresh_tokens_token_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  rotated_at timestamptz,
  revoked_at timestamptz
);

CREATE INDEX refresh_tokens_session_id_idx ON auth.refresh_tokens (session_id);

COMMENT ON COLUMN auth.refresh_tokens.token_hash IS
  'Lowercase hex SHA-256 of the token. The raw token is never stored: no column can hold it.';
COMMENT ON COLUMN auth.refresh_tokens.rotated_at IS 'Set when the token was exchanged for a new one.';
COMMENT ON COLUMN auth.refresh_tokens.revoked_at IS 'Set when the token (or its whole session) was revoked.';
