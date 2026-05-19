package dev.servereer.zpeer.common.proto;

// Wire format: 1-byte type, 3-byte big-endian length, JSON payload.
// Control-channel frames: HELLO..HEARTBEAT, ERROR.
// Pool-socket frames:     POOL_HELLO..ATTACH. After ATTACH, the pool socket
//                         flips to raw byte-bridge mode (no more framing).
public enum FrameType {
    HELLO          (0x01),
    HELLO_OK       (0x02),
    HELLO_ERR      (0x03),
    POOL_TARGET    (0x04),
    HEARTBEAT      (0x05),

    POOL_HELLO     (0x10),
    POOL_HELLO_OK  (0x11),
    POOL_HELLO_ERR (0x12),
    ATTACH         (0x13),

    ERROR          (0x7F);

    private static final FrameType[] BY_CODE = new FrameType[256];
    static {
        for (FrameType t : values()) BY_CODE[t.code] = t;
    }

    public final int code;

    FrameType(int code) { this.code = code; }

    public static FrameType byCode(int code) {
        if (code < 0 || code > 255) return null;
        return BY_CODE[code];
    }
}
