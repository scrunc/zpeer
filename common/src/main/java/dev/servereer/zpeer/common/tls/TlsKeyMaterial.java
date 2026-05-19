package dev.servereer.zpeer.common.tls;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SimpleTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import dev.servereer.zpeer.common.ZPeerConstants;

// Self-signed cert + key pair used for TLS between proxy and backend. The
// proxy generates this on first start and persists it; the backend pins the
// proxy's SHA-256 fingerprint (no CA trust chain, no hostname validation).
public final class TlsKeyMaterial {

    public final X509Certificate certificate;
    public final PrivateKey privateKey;
    public final byte[] sha256Fingerprint;

    private TlsKeyMaterial(X509Certificate cert, PrivateKey key)
            throws CertificateException, NoSuchAlgorithmException {
        this.certificate = cert;
        this.privateKey  = key;
        this.sha256Fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
    }

    public String fingerprintHex() {
        return HexFormat.of().formatHex(sha256Fingerprint);
    }

    public String fingerprintLabel() {
        return "sha256:" + fingerprintHex();
    }

    /** Load existing cert+key from disk; if missing, generate a new pair and persist. */
    public static TlsKeyMaterial loadOrGenerate(Path certPem, Path keyPem) throws Exception {
        if (Files.exists(certPem) && Files.exists(keyPem)) {
            return new TlsKeyMaterial(readCert(certPem), readPrivateKey(keyPem));
        }
        Files.createDirectories(certPem.getParent() != null ? certPem.getParent() : Path.of("."));
        SelfSignedCertificate ssc = new SelfSignedCertificate("zpeer-proxy");
        try {
            X509Certificate cert = ssc.cert();
            PrivateKey key       = ssc.key();
            Files.writeString(certPem, toPem("CERTIFICATE", cert.getEncoded()));
            Files.writeString(keyPem,  toPem("PRIVATE KEY", key.getEncoded()));
            return new TlsKeyMaterial(cert, key);
        } finally {
            ssc.delete();
        }
    }

    public SslContext serverContext() throws Exception {
        return SslContextBuilder.forServer(privateKey, certificate)
                .sslProvider(SslProvider.JDK)
                .protocols(ZPeerConstants.TLS_PROTOCOLS)
                .build();
    }

    /** Build a client TLS context that validates the server cert by exact SHA-256 fingerprint match. */
    public static SslContext clientContextPinned(byte[] expectedFingerprint) throws Exception {
        return SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK)
                .protocols(ZPeerConstants.TLS_PROTOCOLS)
                .trustManager(new PinnedFingerprintTmf(expectedFingerprint))
                .build();
    }

    public static byte[] parseFingerprint(String s) {
        String hex = s.trim();
        if (hex.toLowerCase().startsWith("sha256:")) hex = hex.substring(7);
        hex = hex.replace(":", "").replace("-", "").trim();
        if (hex.length() != 64) {
            throw new IllegalArgumentException(
                    "expected 64-char SHA-256 hex (with optional 'sha256:' prefix), got: " + s);
        }
        return HexFormat.of().parseHex(hex);
    }

    // -------------------- PEM helpers --------------------

    private static String toPem(String type, byte[] der) {
        StringBuilder sb = new StringBuilder("-----BEGIN ").append(type).append("-----\n");
        String b64 = Base64.getEncoder().encodeToString(der);
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(type).append("-----\n");
        return sb.toString();
    }

    private static X509Certificate readCert(Path p) throws IOException, CertificateException {
        try (var in = Files.newInputStream(p)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static PrivateKey readPrivateKey(Path p) throws IOException, GeneralSecurityException {
        String pem = Files.readString(p);
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                         .replace("-----END PRIVATE KEY-----", "")
                         .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    // -------------------- pinned-fingerprint trust manager --------------------

    private static final class PinnedFingerprintTmf extends SimpleTrustManagerFactory {
        private final TrustManager[] tms;

        PinnedFingerprintTmf(byte[] expected) {
            X509TrustManager pin = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("server presented empty cert chain");
                    }
                    byte[] actual;
                    try {
                        actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    } catch (NoSuchAlgorithmException e) {
                        throw new CertificateException("SHA-256 unavailable", e);
                    }
                    if (!Arrays.equals(expected, actual)) {
                        throw new CertificateException(
                                "zpeer cert pin mismatch — expected sha256:"
                                        + HexFormat.of().formatHex(expected)
                                        + " got sha256:" + HexFormat.of().formatHex(actual));
                    }
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            this.tms = new TrustManager[] { pin };
        }

        @Override protected void engineInit(KeyStore keyStore) {}
        @Override protected void engineInit(ManagerFactoryParameters spec) {}
        @Override protected TrustManager[] engineGetTrustManagers() { return tms; }
    }
}
