package dev.servereer.zpeer.common;

public final class ZPeerConstants {
    public static final int PROTOCOL_VERSION = 2;          // v2: TLS-mandatory

    public static final int DEFAULT_CONTROL_PORT = 25577;
    public static final int DEFAULT_POOL_TARGET  = 8;

    public static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    public static final long HEARTBEAT_TIMEOUT_MS  = 30_000L;

    public static final long RECONNECT_INITIAL_MS = 1_000L;
    public static final long RECONNECT_MAX_MS     = 30_000L;

    public static final long ATTACH_TIMEOUT_MS = 5_000L;

    public static final String[] TLS_PROTOCOLS = { "TLSv1.3" };
    public static final long TLS_HANDSHAKE_TIMEOUT_MS = 10_000L;

    private ZPeerConstants() {}
}
