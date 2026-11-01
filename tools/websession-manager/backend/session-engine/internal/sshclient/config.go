package sshclient

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
)

// AuthType represents the SSH authentication method.
type AuthType string

const (
	AuthTypePassword      AuthType = "password"
	AuthTypeKey           AuthType = "key"
	AuthTypeKeyPassphrase AuthType = "key_passphrase"
)

// Config holds all parameters required to dial and authenticate an SSH session.
type Config struct {
	Host              string              // Target remote hostname or IP
	Port              int                 // SSH port (defaults to 22 if <= 0)
	Username          string              // Remote SSH username
	AuthType          AuthType            // "password", "key", or "key_passphrase"
	Password          string              // Remote account password (for AuthTypePassword)
	PrivateKey        []byte              // Raw PEM/OpenSSH private key bytes (for AuthTypeKey/AuthTypeKeyPassphrase)
	Passphrase        string              // Decryption passphrase for encrypted private keys
	ConnectTimeout    time.Duration       // TCP dial & SSH handshake timeout (default: 10s)
	KeepAliveInterval time.Duration       // Interval for sending SSH keepalive requests (default: 30s; 0 disables)
	HostKeyCallback   ssh.HostKeyCallback // Server host-key verification callback (defaults to InsecureIgnoreHostKey)
}

// Validate validates that essential connection and authentication fields are present.
func (c *Config) Validate() error {
	if strings.TrimSpace(c.Host) == "" {
		return errors.New("host cannot be empty")
	}
	if strings.TrimSpace(c.Username) == "" {
		return errors.New("username cannot be empty")
	}

	// Auto-detect AuthType if omitted
	if c.AuthType == "" {
		if len(c.PrivateKey) > 0 {
			if c.Passphrase != "" {
				c.AuthType = AuthTypeKeyPassphrase
			} else {
				c.AuthType = AuthTypeKey
			}
		} else if c.Password != "" {
			c.AuthType = AuthTypePassword
		} else {
			return errors.New("authentication credentials required: password or private key must be provided")
		}
	}

	switch c.AuthType {
	case AuthTypePassword:
		// Password can technically be empty on some test systems, but AuthType is confirmed
	case AuthTypeKey:
		if len(c.PrivateKey) == 0 {
			return errors.New("private key bytes cannot be empty for key authentication")
		}
	case AuthTypeKeyPassphrase:
		if len(c.PrivateKey) == 0 {
			return errors.New("private key bytes cannot be empty for key_passphrase authentication")
		}
		if c.Passphrase == "" {
			return errors.New("passphrase cannot be empty for key_passphrase authentication")
		}
	default:
		return fmt.Errorf("unsupported authentication type: %q", c.AuthType)
	}

	return nil
}

// BuildClientConfig constructs a standard *ssh.ClientConfig configured with the
// appropriate authentication method, timeouts, and host key verification callback.
func (c *Config) BuildClientConfig() (*ssh.ClientConfig, error) {
	if err := c.Validate(); err != nil {
		return nil, NewSSHError(ErrCodeInternal, "config validation failed", err)
	}

	var authMethods []ssh.AuthMethod

	switch c.AuthType {
	case AuthTypePassword:
		authMethods = append(authMethods, ssh.Password(c.Password))

	case AuthTypeKey:
		signer, err := ssh.ParsePrivateKey(c.PrivateKey)
		if err != nil {
			var passphraseMissingErr *ssh.PassphraseMissingError
			if errors.As(err, &passphraseMissingErr) {
				return nil, NewSSHError(ErrCodeAuthFailed, "private key is encrypted but no passphrase was provided", err)
			}
			return nil, NewSSHError(ErrCodeAuthFailed, "failed to parse private key", err)
		}
		authMethods = append(authMethods, ssh.PublicKeys(signer))

	case AuthTypeKeyPassphrase:
		signer, err := ssh.ParsePrivateKeyWithPassphrase(c.PrivateKey, []byte(c.Passphrase))
		if err != nil {
			return nil, NewSSHError(ErrCodeAuthFailed, "failed to decrypt private key with provided passphrase", err)
		}
		authMethods = append(authMethods, ssh.PublicKeys(signer))
	}

	timeout := c.ConnectTimeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}

	hostKeyCallback := c.HostKeyCallback
	if hostKeyCallback == nil {
		hostKeyCallback = ssh.InsecureIgnoreHostKey()
	}

	return &ssh.ClientConfig{
		User:            c.Username,
		Auth:            authMethods,
		HostKeyCallback: hostKeyCallback,
		Timeout:         timeout,
	}, nil
}

// ZeroSecrets securely clears password, passphrase, and private key bytes
// from memory to prevent credential leakage (PRD FR-E6, Module-And-API §9).
func (c *Config) ZeroSecrets() {
	c.Password = ""
	c.Passphrase = ""
	for i := range c.PrivateKey {
		c.PrivateKey[i] = 0
	}
	c.PrivateKey = nil
}
