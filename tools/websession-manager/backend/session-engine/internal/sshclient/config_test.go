package sshclient

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/pem"
	"testing"
	"time"
)

// generateTestEd25519Key generates an unencrypted PEM encoded Ed25519 private key.
func generateTestEd25519Key(t *testing.T) []byte {
	t.Helper()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate ed25519 key: %v", err)
	}

	der, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		t.Fatalf("failed to marshal pkcs8: %v", err)
	}

	return pem.EncodeToMemory(&pem.Block{
		Type:  "PRIVATE KEY",
		Bytes: der,
	})
}

// generateTestEncryptedRSAKey generates a passphrase-encrypted PEM private key.
func generateTestEncryptedRSAKey(t *testing.T, passphrase string) []byte {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("failed to generate rsa key: %v", err)
	}

	der := x509.MarshalPKCS1PrivateKey(key)
	// Deprecated in stdlib but fully supported by crypto/ssh for legacy PEM compatibility
	block, err := x509.EncryptPEMBlock(rand.Reader, "RSA PRIVATE KEY", der, []byte(passphrase), x509.PEMCipherAES256)
	if err != nil {
		t.Fatalf("failed to encrypt pem block: %v", err)
	}

	return pem.EncodeToMemory(block)
}

func TestConfig_Validate(t *testing.T) {
	validKey := generateTestEd25519Key(t)

	tests := []struct {
		name    string
		cfg     Config
		wantErr bool
	}{
		{
			name: "valid password auth",
			cfg: Config{
				Host:     "127.0.0.1",
				Port:     22,
				Username: "root",
				AuthType: AuthTypePassword,
				Password: "secret",
			},
			wantErr: false,
		},
		{
			name: "valid key auth",
			cfg: Config{
				Host:       "127.0.0.1",
				Port:       22,
				Username:   "root",
				AuthType:   AuthTypeKey,
				PrivateKey: validKey,
			},
			wantErr: false,
		},
		{
			name: "valid key with passphrase auth",
			cfg: Config{
				Host:       "127.0.0.1",
				Port:       22,
				Username:   "root",
				AuthType:   AuthTypeKeyPassphrase,
				PrivateKey: validKey,
				Passphrase: "my-secret-phrase",
			},
			wantErr: false,
		},
		{
			name: "missing host",
			cfg: Config{
				Host:     "",
				Username: "root",
				Password: "secret",
			},
			wantErr: true,
		},
		{
			name: "missing username",
			cfg: Config{
				Host:     "127.0.0.1",
				Username: "",
				Password: "secret",
			},
			wantErr: true,
		},
		{
			name: "missing all credentials",
			cfg: Config{
				Host:     "127.0.0.1",
				Username: "root",
			},
			wantErr: true,
		},
		{
			name: "key auth with empty private key",
			cfg: Config{
				Host:       "127.0.0.1",
				Username:   "root",
				AuthType:   AuthTypeKey,
				PrivateKey: nil,
			},
			wantErr: true,
		},
		{
			name: "key_passphrase auth missing passphrase",
			cfg: Config{
				Host:       "127.0.0.1",
				Username:   "root",
				AuthType:   AuthTypeKeyPassphrase,
				PrivateKey: validKey,
				Passphrase: "",
			},
			wantErr: true,
		},
		{
			name: "unsupported auth type",
			cfg: Config{
				Host:     "127.0.0.1",
				Username: "root",
				AuthType: "kerberos",
				Password: "secret",
			},
			wantErr: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			err := tc.cfg.Validate()
			if (err != nil) != tc.wantErr {
				t.Fatalf("Validate() error = %v, wantErr %v", err, tc.wantErr)
			}
		})
	}
}

func TestConfig_BuildClientConfig(t *testing.T) {
	t.Run("password auth", func(t *testing.T) {
		cfg := Config{
			Host:           "127.0.0.1",
			Port:           22,
			Username:       "alice",
			AuthType:       AuthTypePassword,
			Password:       "p4ssw0rd",
			ConnectTimeout: 5 * time.Second,
		}

		clientCfg, err := cfg.BuildClientConfig()
		if err != nil {
			t.Fatalf("BuildClientConfig failed: %v", err)
		}

		if clientCfg.User != "alice" {
			t.Errorf("expected user alice, got %s", clientCfg.User)
		}
		if len(clientCfg.Auth) != 1 {
			t.Errorf("expected 1 auth method, got %d", len(clientCfg.Auth))
		}
		if clientCfg.Timeout != 5*time.Second {
			t.Errorf("expected timeout 5s, got %v", clientCfg.Timeout)
		}
	})

	t.Run("unencrypted key auth", func(t *testing.T) {
		keyBytes := generateTestEd25519Key(t)
		cfg := Config{
			Host:       "127.0.0.1",
			Username:   "bob",
			AuthType:   AuthTypeKey,
			PrivateKey: keyBytes,
		}

		clientCfg, err := cfg.BuildClientConfig()
		if err != nil {
			t.Fatalf("BuildClientConfig failed: %v", err)
		}
		if clientCfg.User != "bob" {
			t.Errorf("expected user bob, got %s", clientCfg.User)
		}
		if len(clientCfg.Auth) != 1 {
			t.Errorf("expected 1 auth method, got %d", len(clientCfg.Auth))
		}
	})

	t.Run("encrypted key with passphrase auth", func(t *testing.T) {
		passphrase := "super-secure-phrase"
		encryptedKey := generateTestEncryptedRSAKey(t, passphrase)

		cfg := Config{
			Host:       "127.0.0.1",
			Username:   "charlie",
			AuthType:   AuthTypeKeyPassphrase,
			PrivateKey: encryptedKey,
			Passphrase: passphrase,
		}

		clientCfg, err := cfg.BuildClientConfig()
		if err != nil {
			t.Fatalf("BuildClientConfig with passphrase failed: %v", err)
		}
		if clientCfg.User != "charlie" {
			t.Errorf("expected user charlie, got %s", clientCfg.User)
		}
		if len(clientCfg.Auth) != 1 {
			t.Errorf("expected 1 auth method, got %d", len(clientCfg.Auth))
		}
	})

	t.Run("encrypted key missing passphrase error", func(t *testing.T) {
		passphrase := "super-secure-phrase"
		encryptedKey := generateTestEncryptedRSAKey(t, passphrase)

		cfg := Config{
			Host:       "127.0.0.1",
			Username:   "dave",
			AuthType:   AuthTypeKey,
			PrivateKey: encryptedKey,
		}

		_, err := cfg.BuildClientConfig()
		if err == nil {
			t.Fatalf("expected error for encrypted key without passphrase, got nil")
		}
		classified := ClassifyError(err)
		if classified.Code != ErrCodeAuthFailed {
			t.Errorf("expected ErrCodeAuthFailed, got %q", classified.Code)
		}
	})

	t.Run("encrypted key wrong passphrase error", func(t *testing.T) {
		passphrase := "correct-phrase"
		encryptedKey := generateTestEncryptedRSAKey(t, passphrase)

		cfg := Config{
			Host:       "127.0.0.1",
			Username:   "eve",
			AuthType:   AuthTypeKeyPassphrase,
			PrivateKey: encryptedKey,
			Passphrase: "wrong-phrase",
		}

		_, err := cfg.BuildClientConfig()
		if err == nil {
			t.Fatalf("expected error for wrong passphrase, got nil")
		}
		classified := ClassifyError(err)
		if classified.Code != ErrCodeAuthFailed {
			t.Errorf("expected ErrCodeAuthFailed, got %q", classified.Code)
		}
	})
}

func TestConfig_ZeroSecrets(t *testing.T) {
	key := []byte("fake-private-key-bytes-123456789")
	cfg := Config{
		Host:       "127.0.0.1",
		Username:   "admin",
		Password:   "secret-pass",
		PrivateKey: key,
		Passphrase: "secret-phrase",
	}

	cfg.ZeroSecrets()

	if cfg.Password != "" {
		t.Errorf("expected Password to be wiped, got %q", cfg.Password)
	}
	if cfg.Passphrase != "" {
		t.Errorf("expected Passphrase to be wiped, got %q", cfg.Passphrase)
	}
	if cfg.PrivateKey != nil {
		t.Errorf("expected PrivateKey slice to be nil, got %v", cfg.PrivateKey)
	}
	// Verify original backing slice was zeroed
	for i, b := range key {
		if b != 0 {
			t.Errorf("expected byte at index %d to be zeroed, got %d", i, b)
		}
	}
}
