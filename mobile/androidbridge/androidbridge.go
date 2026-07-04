package androidbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	"sskycn/tcptun"
)

type LogCallback interface {
	OnLog(line string)
}

type mobileConfig struct {
	Mode                  string     `json:"mode"`
	ListenAddrs           stringList `json:"listen_addrs"`
	LocalListenAddr       string     `json:"local_listen_addr"`
	ServerAddr            string     `json:"server_addr"`
	Token                 string     `json:"token"`
	TunnelProtocol        string     `json:"tunnel_protocol"`
	TunnelTransport       string     `json:"tunnel_transport"`
	TunnelPath            string     `json:"tunnel_path"`
	TunnelTLS             bool       `json:"tunnel_tls"`
	TunnelTLSServerName   string     `json:"tunnel_tls_server_name"`
	TunnelTLSInsecure     bool       `json:"tunnel_tls_insecure"`
	TunnelSecurity        string     `json:"tunnel_security"`
	TunnelFlow            string     `json:"tunnel_flow"`
	RealityServerName     string     `json:"reality_server_name"`
	RealityFingerprint    string     `json:"reality_fingerprint"`
	RealityPublicKey      string     `json:"reality_public_key"`
	RealityShortID        string     `json:"reality_short_id"`
	RealitySpiderX        string     `json:"reality_spider_x"`
	TunnelMux             bool       `json:"tunnel_mux"`
	UpstreamProtocol      string     `json:"upstream_protocol"`
	EnableUDP             bool       `json:"enable_udp"`
	ConfigPath            string     `json:"config_path"`
	RouteConfigPath       string     `json:"route_config_path"`
	DirectProbeTimeout    string     `json:"direct_probe_timeout"`
	HeartbeatInterval     string     `json:"heartbeat_interval"`
	ConnectionIdleTimeout string     `json:"connection_idle_timeout"`
	UDPSessionTimeout     string     `json:"udp_session_timeout"`
	RetryInitialInterval  string     `json:"retry_initial_interval"`
	RetryMaxInterval      string     `json:"retry_max_interval"`
	Verbose               bool       `json:"verbose"`
}

type stringList []string

func (l *stringList) UnmarshalJSON(data []byte) error {
	var items []string
	if err := json.Unmarshal(data, &items); err == nil {
		*l = items
		return nil
	}
	var text string
	if err := json.Unmarshal(data, &text); err != nil {
		return err
	}
	text = strings.TrimSpace(text)
	if text == "" {
		*l = nil
		return nil
	}
	if strings.HasPrefix(text, "[") {
		if err := json.Unmarshal([]byte(text), &items); err == nil {
			*l = items
			return nil
		}
	}
	*l = strings.Split(text, ",")
	return nil
}

var runtimeState = struct {
	sync.Mutex
	runID  uint64
	cancel context.CancelFunc
	done   chan error
	status string
	log    LogCallback
}{
	status: "Stopped",
}

func SetLogCallback(cb LogCallback) {
	runtimeState.Lock()
	runtimeState.log = cb
	runtimeState.Unlock()
}

func Start(configJSON string) error {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		setStatus("Error")
		logLine("start failed: " + err.Error())
		return err
	}

	runtimeState.Lock()
	if runtimeState.cancel != nil {
		runtimeState.Unlock()
		return errors.New("tcptun is already running")
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	runtimeState.runID++
	runID := runtimeState.runID
	runtimeState.cancel = cancel
	runtimeState.done = done
	runtimeState.status = "Starting"
	runtimeState.Unlock()

	logLine("starting tcptun client")
	logOutput := io.Writer(io.Discard)
	if cfg.Verbose {
		logOutput = logWriter{}
	}
	go func() {
		err := tcptun.RunProxy(ctx, cfg, logOutput)
		done <- err
		runtimeState.Lock()
		if runtimeState.runID == runID {
			runtimeState.cancel = nil
			runtimeState.done = nil
			if err != nil && ctx.Err() == nil {
				runtimeState.status = "Error"
			} else {
				runtimeState.status = "Stopped"
			}
		}
		runtimeState.Unlock()
		if err != nil && ctx.Err() == nil {
			logLine("tcptun stopped with error: " + err.Error())
		} else {
			logLine("tcptun stopped")
		}
	}()

	select {
	case err := <-done:
		runtimeState.Lock()
		if runtimeState.runID == runID {
			runtimeState.cancel = nil
			runtimeState.done = nil
			runtimeState.status = "Error"
		}
		runtimeState.Unlock()
		if err == nil {
			err = errors.New("tcptun stopped during startup")
		}
		logLine("start failed: " + err.Error())
		return err
	case <-time.After(250 * time.Millisecond):
		setStatus("Running")
		logLine("tcptun client running")
		return nil
	}
}

func Stop() error {
	runtimeState.Lock()
	cancel := runtimeState.cancel
	done := runtimeState.done
	if cancel == nil {
		runtimeState.status = "Stopped"
		runtimeState.Unlock()
		return nil
	}
	runtimeState.status = "Stopped"
	runtimeState.cancel = nil
	runtimeState.done = nil
	runtimeState.Unlock()

	cancel()
	if done == nil {
		return nil
	}
	select {
	case err := <-done:
		if err != nil && !errors.Is(err, context.Canceled) {
			return err
		}
		return nil
	case <-time.After(3 * time.Second):
		return errors.New("timeout waiting for tcptun to stop")
	}
}

func Status() string {
	runtimeState.Lock()
	defer runtimeState.Unlock()
	return runtimeState.status
}

func parseConfig(configJSON string) (tcptun.Config, error) {
	var in mobileConfig
	if err := json.Unmarshal([]byte(configJSON), &in); err != nil {
		return tcptun.Config{}, err
	}
	cfg := tcptun.DefaultConfig()
	cfg.Mode = valueOrDefault(in.Mode, tcptun.ProxyModeClient)
	cfg.ListenAddrs = normalizeListenAddrs([]string(in.ListenAddrs), in.LocalListenAddr)
	cfg.ListenAddr = ""
	cfg.ServerAddr = strings.TrimSpace(in.ServerAddr)
	cfg.Token = strings.TrimSpace(in.Token)
	cfg.TunnelProtocol = valueOrDefault(in.TunnelProtocol, tcptun.TunnelProtocolNative)
	cfg.TunnelTransport = valueOrDefault(in.TunnelTransport, tcptun.TunnelTransportRaw)
	cfg.TunnelPath = valueOrDefault(in.TunnelPath, "/proxy")
	cfg.TunnelTLS = in.TunnelTLS
	cfg.TunnelTLSServerName = strings.TrimSpace(in.TunnelTLSServerName)
	cfg.TunnelTLSInsecure = in.TunnelTLSInsecure
	cfg.TunnelSecurity = strings.TrimSpace(in.TunnelSecurity)
	cfg.TunnelFlow = strings.TrimSpace(in.TunnelFlow)
	cfg.RealityServerName = valueOrDefault(in.RealityServerName, in.TunnelTLSServerName)
	cfg.RealityFingerprint = strings.TrimSpace(in.RealityFingerprint)
	cfg.RealityPublicKey = strings.TrimSpace(in.RealityPublicKey)
	cfg.RealityShortID = strings.TrimSpace(in.RealityShortID)
	cfg.RealitySpiderX = strings.TrimSpace(in.RealitySpiderX)
	cfg.TunnelMux = in.TunnelMux
	cfg.UpstreamProtocol = valueOrDefault(in.UpstreamProtocol, tcptun.UpstreamProtocolSOCKS5)
	cfg.ConfigPath = strings.TrimSpace(in.ConfigPath)
	cfg.RouteConfigPath = strings.TrimSpace(in.RouteConfigPath)
	cfg.Verbose = in.Verbose
	var err error
	if cfg.DirectProbeTimeout, err = parseOptionalDuration(in.DirectProbeTimeout, "direct_probe_timeout"); err != nil {
		return tcptun.Config{}, err
	}
	if cfg.HeartbeatInterval, err = parseOptionalDuration(in.HeartbeatInterval, "heartbeat_interval"); err != nil {
		return tcptun.Config{}, err
	}
	if cfg.ConnectionIdleTimeout, err = parseOptionalDuration(in.ConnectionIdleTimeout, "connection_idle_timeout"); err != nil {
		return tcptun.Config{}, err
	}
	if cfg.UDPSessionTimeout, err = parseOptionalDuration(in.UDPSessionTimeout, "udp_session_timeout"); err != nil {
		return tcptun.Config{}, err
	}
	if cfg.RetryInitialInterval, err = parseOptionalDuration(in.RetryInitialInterval, "retry_initial_interval"); err != nil {
		return tcptun.Config{}, err
	}
	if cfg.RetryMaxInterval, err = parseOptionalDuration(in.RetryMaxInterval, "retry_max_interval"); err != nil {
		return tcptun.Config{}, err
	}
	if len(cfg.ListenAddrs) == 0 {
		return tcptun.Config{}, errors.New("local listen address is required")
	}
	if strings.TrimSpace(cfg.ServerAddr) == "" {
		return tcptun.Config{}, errors.New("server address is required")
	}
	return cfg, nil
}

func parseOptionalDuration(value string, name string) (time.Duration, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0, nil
	}
	duration, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("invalid %s %q: %w", name, value, err)
	}
	return duration, nil
}

func normalizeListenAddrs(addrs []string, fallback string) []string {
	out := make([]string, 0, len(addrs)+1)
	seen := make(map[string]struct{}, len(addrs)+1)
	for _, addr := range append(addrs, fallback) {
		addr = strings.TrimSpace(addr)
		if addr == "" {
			continue
		}
		if _, ok := seen[addr]; ok {
			continue
		}
		seen[addr] = struct{}{}
		out = append(out, addr)
	}
	return out
}

func valueOrDefault(value string, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func setStatus(status string) {
	runtimeState.Lock()
	runtimeState.status = status
	runtimeState.Unlock()
}

func logLine(line string) {
	runtimeState.Lock()
	cb := runtimeState.log
	runtimeState.Unlock()
	if cb != nil {
		cb.OnLog(strings.TrimRight(line, "\r\n"))
	}
}

type logWriter struct{}

func (logWriter) Write(p []byte) (int, error) {
	line := strings.TrimRight(string(p), "\r\n")
	if line != "" {
		logLine(line)
	}
	return len(p), nil
}

func init() {
	if strings.TrimSpace(tcptun.Version) == "" {
		panic(fmt.Sprintf("invalid tcptun version %q", tcptun.Version))
	}
}
