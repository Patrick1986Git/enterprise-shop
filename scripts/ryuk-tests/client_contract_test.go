package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/moby/moby/client"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

func TestClientContract(t *testing.T) {
	t.Run("negotiated-ping-failure", func(t *testing.T) {
		tc := newRunTest()
		tc.pingErr = errors.New("daemon unavailable")
		cli := newMockClient(tc)
		r, err := newReaper(context.Background(), discardLogger, testConfig, withClient(cli))
		require.Nil(t, r)
		require.EqualError(t, err, "ping: daemon unavailable")
		cli.AssertCalled(t, "Ping", mockContext, client.PingOptions{NegotiateAPIVersion: true})
	})
	t.Run("session-filter-order-and-deduplication", func(t *testing.T) {
		r := &reaper{filters: make(map[string]client.Filters), logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
		require.NoError(t, r.addFilter("label=org.testcontainers%3Dtrue&label=session%3Done"))
		require.NoError(t, r.addFilter("label=session%3Done&label=org.testcontainers%3Dtrue"))
		require.Equal(t, []client.Filters{make(client.Filters).Add("label", "org.testcontainers=true", "session=one")}, r.filterArgs())
	})
	t.Run("cleanup-order-and-options", func(t *testing.T) {
		cli := newMockClient(newRunTest())
		r := &reaper{client: cli, cfg: &config{RemoveRetries: 1, RequestTimeout: time.Second},
			logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
		require.NoError(t, r.prune(&resources{containers: []string{containerID1}, networks: []string{networkID1},
			volumes: []string{volumeName1}, images: []string{imageID1}}))
		methods := make([]string, 0, len(cli.Calls))
		for _, call := range cli.Calls {
			methods = append(methods, call.Method)
		}
		require.Equal(t, []string{"ContainerRemove", "NetworkRemove", "VolumeRemove", "ImageRemove"}, methods)
		require.Equal(t, client.ContainerRemoveOptions{Force: true, RemoveVolumes: true}, cli.Calls[0].Arguments[2])
		require.Equal(t, client.NetworkRemoveOptions{}, cli.Calls[1].Arguments[2])
		require.Equal(t, client.VolumeRemoveOptions{Force: true}, cli.Calls[2].Arguments[2])
		require.Equal(t, client.ImageRemoveOptions{PruneChildren: true}, cli.Calls[3].Arguments[2])
	})
	t.Run("transient-removal-retry", func(t *testing.T) {
		cli := &mockClient{}
		cli.On("ContainerRemove", mockContext, containerID1, containerRemoveOptions).Return(errors.New("busy")).Once()
		cli.On("ContainerRemove", mockContext, containerID1, containerRemoveOptions).Return(nil).Once()
		r := &reaper{client: cli, cfg: &config{RemoveRetries: 2, RequestTimeout: time.Second},
			logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
		require.NoError(t, r.prune(&resources{containers: []string{containerID1}}))
		cli.AssertExpectations(t)
	})
}
