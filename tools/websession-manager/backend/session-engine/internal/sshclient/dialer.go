package sshclient

import (
	"time"

	"golang.org/x/crypto/ssh"
)

func DialSSH(username, password string) (*ssh.ClientConfig, error) {
	return &ssh.ClientConfig{
		User: username,
		Auth: []ssh.AuthMethod{
			ssh.Password(password),
		},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         5 * time.Second,
	}, nil
}
