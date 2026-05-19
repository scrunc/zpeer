package dev.servereer.zpeer.backend;

import org.bukkit.configuration.file.FileConfiguration;

public final class BackendConfig {

    public final String proxyHost;
    public final int    proxyPort;
    public final String certFingerprint;     // required; SHA-256 hex (with or without "sha256:" prefix)
    public final String token;
    public final String localHost;
    public final int    localPort;

    public BackendConfig(FileConfiguration c) {
        this.proxyHost       = c.getString("proxy.host", "127.0.0.1");
        this.proxyPort       = c.getInt("proxy.port", 25577);
        this.certFingerprint = c.getString("proxy.cert_fingerprint", "");
        this.token           = c.getString("token", "");
        this.localHost       = c.getString("local.host", "127.0.0.1");
        this.localPort       = c.getInt("local.port", 25565);
    }

    public boolean isValid() {
        if (token.isBlank() || "REPLACE_ME".equals(token)) return false;
        if (certFingerprint.isBlank() || certFingerprint.startsWith("REPLACE")) return false;
        return true;
    }
}
