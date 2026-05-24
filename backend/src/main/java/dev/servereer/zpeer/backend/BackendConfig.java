package dev.servereer.zpeer.backend;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Top-level backend config. One Paper backend can attach to one or more
 * zpeer proxies simultaneously.
 *
 * Schemas accepted:
 *
 *   Preferred (multi-proxy):
 *     proxies:
 *       - name: royal
 *         host: 185.207.166.244
 *         port: 24219
 *         cert_fingerprint: "sha256:..."
 *         token: "..."
 *       - name: cennocraft
 *         host: 216.163.186.44
 *         port: 12003
 *         cert_fingerprint: "sha256:..."
 *         token: "..."
 *     local:
 *       host: 127.0.0.1
 *       port: 25565
 *
 *   Legacy (single-proxy, still supported):
 *     proxy:
 *       host: 127.0.0.1
 *       port: 25577
 *       cert_fingerprint: "sha256:..."
 *     token: "..."
 *     local:
 *       host: 127.0.0.1
 *       port: 25565
 */
public final class BackendConfig {

    public final List<ProxyConfig> proxies;
    public final String localHost;
    public final int    localPort;

    public BackendConfig(FileConfiguration c) {
        this.localHost = c.getString("local.host", "127.0.0.1");
        this.localPort = c.getInt("local.port", 25565);

        List<ProxyConfig> list = new ArrayList<>();

        // ---- New schema: proxies: [ {name, host, port, cert_fingerprint, token}, ... ] ----
        List<?> rawList = c.getList("proxies");
        if (rawList != null) {
            int i = 0;
            for (Object o : rawList) {
                if (!(o instanceof Map<?, ?> m)) continue;
                i++;
                String name = strOr(m.get("name"), "proxy-" + i);
                String host = strOr(m.get("host"), "");
                int    port = intOr(m.get("port"), 25577);
                String cert = strOr(m.get("cert_fingerprint"), "");
                String tok  = strOr(m.get("token"), "");
                list.add(new ProxyConfig(name, host, port, cert, tok,
                        localHost, localPort));
            }
        }

        // ---- Legacy schema: proxy: {...} + token at top level ----
        if (list.isEmpty() && c.isConfigurationSection("proxy")) {
            String host = c.getString("proxy.host", "127.0.0.1");
            int    port = c.getInt("proxy.port", 25577);
            String cert = c.getString("proxy.cert_fingerprint", "");
            String tok  = c.getString("token", "");
            list.add(new ProxyConfig("default", host, port, cert, tok,
                    localHost, localPort));
        }

        this.proxies = list;
    }

    public boolean isValid() {
        if (proxies.isEmpty()) return false;
        for (ProxyConfig p : proxies) {
            if (!p.isValid()) return false;
        }
        return true;
    }

    private static String strOr(Object v, String dflt) {
        return v == null ? dflt : v.toString();
    }

    private static int intOr(Object v, int dflt) {
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (Exception e) { return dflt; }
    }
}
