package dev.servereer.zpeer.proxy.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ProxyConfig {

    public final String listenHost;
    public final int    listenPort;
    public final int    poolTarget;
    public final Map<String, String> tokenToServer;
    public final String tlsCertFile;        // path relative to plugin data dir
    public final String tlsKeyFile;

    private ProxyConfig(String host, int port, int poolTarget,
                        Map<String, String> tokens,
                        String tlsCertFile, String tlsKeyFile) {
        this.listenHost    = host;
        this.listenPort    = port;
        this.poolTarget    = poolTarget;
        this.tokenToServer = Collections.unmodifiableMap(tokens);
        this.tlsCertFile   = tlsCertFile;
        this.tlsKeyFile    = tlsKeyFile;
    }

    public static ProxyConfig loadOrCreate(Path dataDir) throws IOException {
        Path configPath = dataDir.resolve("config.yml");
        if (!Files.exists(configPath)) {
            Files.createDirectories(dataDir);
            try (InputStream in = ProxyConfig.class.getResourceAsStream("/config.yml")) {
                if (in == null) throw new IOException("bundled config.yml missing");
                Files.copy(in, configPath);
            }
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            Map<String, Object> root = new Yaml().load(in);
            return parse(root);
        }
    }

    @SuppressWarnings("unchecked")
    private static ProxyConfig parse(Map<String, Object> root) {
        Map<String, Object> listen = (Map<String, Object>) root.getOrDefault("listen", Map.of());
        String host = (String) listen.getOrDefault("host", "0.0.0.0");
        int    port = ((Number) listen.getOrDefault("port", 25577)).intValue();
        int    pool = ((Number) root.getOrDefault("pool-target", 8)).intValue();

        Map<String, String> tokens = new HashMap<>();
        Object rawTokens = root.get("tokens");
        if (rawTokens instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                tokens.put(e.getKey().toString(), e.getValue().toString());
            }
        }

        Map<String, Object> tls = (Map<String, Object>) root.getOrDefault("tls", Map.of());
        String certFile = (String) tls.getOrDefault("cert-file", "data/proxy.crt");
        String keyFile  = (String) tls.getOrDefault("key-file",  "data/proxy.key");

        return new ProxyConfig(host, port, pool, tokens, certFile, keyFile);
    }
}
