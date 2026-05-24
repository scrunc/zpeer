package dev.servereer.zpeer.backend;

/**
 * Connection settings for a single zpeer proxy entry. The local
 * Minecraft host/port are shared across all proxies on the same backend.
 */
public final class ProxyConfig {

    public final String name;            // human label used in log lines
    public final String host;
    public final int    port;
    public final String certFingerprint; // SHA-256 hex (with or without "sha256:" prefix)
    public final String token;
    public final String localHost;
    public final int    localPort;

    public ProxyConfig(String name, String host, int port,
                       String certFingerprint, String token,
                       String localHost, int localPort) {
        this.name            = name;
        this.host            = host;
        this.port            = port;
        this.certFingerprint = certFingerprint;
        this.token           = token;
        this.localHost       = localHost;
        this.localPort       = localPort;
    }

    public boolean isValid() {
        if (token == null || token.isBlank() || token.startsWith("REPLACE")) return false;
        if (certFingerprint == null || certFingerprint.isBlank()
                || certFingerprint.startsWith("REPLACE")) return false;
        if (host == null || host.isBlank()) return false;
        return port > 0 && port <= 65535;
    }

    /** Convenience log prefix used by all per-proxy net classes. */
    public String logTag() {
        return "[zpeer/" + name + "]";
    }
}
