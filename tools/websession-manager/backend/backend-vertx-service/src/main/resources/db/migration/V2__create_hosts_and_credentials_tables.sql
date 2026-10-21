-- Migration V2: Create credentials and hosts tables

CREATE TABLE IF NOT EXISTS credentials
(
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    username VARCHAR(64) NOT NULL,
    auth_type VARCHAR(32) NOT NULL DEFAULT 'password',
    encrypted_secret TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_credentials_user_id ON credentials(user_id);

CREATE TABLE IF NOT EXISTS hosts
(
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    address VARCHAR(255) NOT NULL,
    port INT NOT NULL DEFAULT 22,
    credential_id VARCHAR(36) REFERENCES credentials(id) ON DELETE SET NULL,
    color VARCHAR(32),
    tags VARCHAR(255),
    favorite BOOLEAN NOT NULL DEFAULT FALSE,
    post_connect_script TEXT,
    logging_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    agent_id VARCHAR(36),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_hosts_user_id ON hosts(user_id);
CREATE INDEX IF NOT EXISTS idx_hosts_credential_id ON hosts(credential_id);
