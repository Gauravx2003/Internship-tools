package sshclient

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
	"golang.org/x/crypto/ssh/knownhosts"
)

// InteractivePrompter defines an interface for interacting with the user terminal
// during SSH host key verification (TOFU prompt).
type InteractivePrompter interface {
	Prompt(msg string) error
	ReadLine(ctx context.Context) (string, error)
}

// AppendToKnownHosts appends a verified public key line to the specified known_hosts file.
// If knownHostsPath is empty, it defaults to ~/.ssh/known_hosts.
func AppendToKnownHosts(knownHostsPath, hostname string, remote net.Addr, key ssh.PublicKey) error {
	if strings.TrimSpace(knownHostsPath) == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return fmt.Errorf("failed to get user home directory: %w", err)
		}
		knownHostsPath = filepath.Join(home, ".ssh", "known_hosts")
	}

	dir := filepath.Dir(knownHostsPath)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return fmt.Errorf("failed to create directory %s: %w", dir, err)
	}

	addresses := []string{knownhosts.Normalize(hostname)}
	if remote != nil {
		remoteNorm := knownhosts.Normalize(remote.String())
		if remoteNorm != addresses[0] {
			addresses = append(addresses, remoteNorm)
		}
	}

	line := knownhosts.Line(addresses, key) + "\n"

	f, err := os.OpenFile(knownHostsPath, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0600)
	if err != nil {
		return fmt.Errorf("failed to open %s: %w", knownHostsPath, err)
	}
	defer f.Close()

	stat, err := f.Stat()
	if err == nil && stat.Size() > 0 {
		buf := make([]byte, 1)
		if _, err := f.ReadAt(buf, stat.Size()-1); err == nil && buf[0] != '\n' {
			line = "\n" + line
		}
	}

	if _, err := f.WriteString(line); err != nil {
		return fmt.Errorf("failed to write to %s: %w", knownHostsPath, err)
	}
	return nil
}

// NewTOFUHostKeyCallback creates an OpenSSH-compliant host key verification callback:
// 1. Checks ~/.ssh/known_hosts (or custom path). If key matches, connects immediately.
// 2. If host key is mismatched (changed), aborts with MITM warning.
// 3. If key is unknown: prompts user interactively in the terminal (yes/no),
//    and upon confirmation saves to known_hosts.
func NewTOFUHostKeyCallback(knownHostsPath string, prompter InteractivePrompter) (ssh.HostKeyCallback, error) {
	if strings.TrimSpace(knownHostsPath) == "" {
		if home, err := os.UserHomeDir(); err == nil && home != "" {
			knownHostsPath = filepath.Join(home, ".ssh", "known_hosts")
		}
	}

	return func(hostname string, remote net.Addr, key ssh.PublicKey) error {
		// 1. Check known_hosts if file exists
		if knownHostsPath != "" {
			if _, err := os.Stat(knownHostsPath); err == nil {
				verifier, err := knownhosts.New(knownHostsPath)
				if err == nil {
					verifyErr := verifier(hostname, remote, key)
					if verifyErr == nil {
						return nil // Trusted!
					}

					var keyErr *knownhosts.KeyError
					if errors.As(verifyErr, &keyErr) {
						if len(keyErr.Want) > 0 {
							return fmt.Errorf("WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED for %s! Potential MITM attack", hostname)
						}
					} else {
						return verifyErr
					}
				}
			}
		}

		// 2. If prompter is not available, cannot prompt interactively
		if prompter == nil {
			return fmt.Errorf("ssh: handshake failed: knownhosts: key is unknown")
		}

		// 3. Interactive OpenSSH prompt
		actualFingerprint := ssh.FingerprintSHA256(key)
		keyType := strings.ToUpper(key.Type())
		hostAddr := hostname
		if remote != nil && remote.String() != hostname {
			hostAddr = fmt.Sprintf("%s (%s)", hostname, remote.String())
		}

		promptMsg := fmt.Sprintf(
			"\r\nThe authenticity of host '%s' can't be established.\r\n%s key fingerprint is %s.\r\nThis key is not known by any other names.\r\nAre you sure you want to continue connecting (yes/no/[fingerprint])? ",
			hostAddr, keyType, actualFingerprint,
		)

		if err := prompter.Prompt(promptMsg); err != nil {
			return fmt.Errorf("failed to prompt terminal: %w", err)
		}

		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()

		resp, err := prompter.ReadLine(ctx)
		if err != nil {
			_ = prompter.Prompt("\r\nHost key verification timed out or canceled.\r\n")
			return fmt.Errorf("host key verification failed: %w", err)
		}

		cleanResp := strings.TrimSpace(strings.ToLower(resp))
		expectedFp := strings.TrimSpace(strings.ToLower(actualFingerprint))
		if cleanResp == "yes" || cleanResp == expectedFp || cleanResp == strings.TrimPrefix(expectedFp, "sha256:") {
			if err := AppendToKnownHosts(knownHostsPath, hostname, remote, key); err != nil {
				_ = prompter.Prompt(fmt.Sprintf("\r\nWarning: Could not save host key to %s: %v\r\n", knownHostsPath, err))
			} else {
				_ = prompter.Prompt(fmt.Sprintf("\r\nWarning: Permanently added '%s' (%s) to the list of known hosts.\r\n", hostname, keyType))
			}
			return nil
		}

		_ = prompter.Prompt("\r\nHost key verification rejected by user.\r\n")
		return errors.New("host key verification rejected by user")
	}, nil
}
