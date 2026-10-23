import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;

/** Offline fixtures only: no production trust store or security properties are changed. */
public class BlocklistRegression {
    private static final char[] PASSWORD = "fixture-only".toCharArray();
    private static final Date FIXTURE_DATE = Date.from(Instant.parse("2026-10-22T12:00:00Z"));

    private static X509Certificate[] certificates(Path path) throws Exception {
        try (InputStream in = Files.newInputStream(path)) {
            return CertificateFactory.getInstance("X.509").generateCertificates(in)
                    .toArray(X509Certificate[]::new);
        }
    }

    private static KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        return store;
    }

    private static X509TrustManager trust(KeyStore store, boolean fixedDate) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
        if (fixedDate) {
            // Match ordinary offline JSSE validation; expiry must not mask blocklist rejection.
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(store, new X509CertSelector());
            parameters.setDate(FIXTURE_DATE);
            parameters.setRevocationEnabled(false);
            factory.init(new CertPathTrustManagerParameters(parameters));
        } else {
            factory.init(store);
        }
        return Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast).findFirst().orElseThrow();
    }

    private static boolean hasMessage(Throwable error, String text) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains(text)) return true;
        }
        return false;
    }

    private static void incidentChains(Path fixtures, boolean blocked) throws Exception {
        for (String kind : new String[]{"ecdsa", "rsa"}) {
            X509Certificate root = certificates(fixtures.resolve(kind + "-root.pem"))[0];
            KeyStore anchors = store();
            anchors.setCertificateEntry("affected-public-root", root);
            X509TrustManager manager = trust(anchors, true);
            X509Certificate[] original = certificates(fixtures.resolve(kind + "-0.pem"));
            for (int variant = 0; variant < 2; variant++) {
                X509Certificate[] chain = certificates(fixtures.resolve(kind + "-" + variant + ".pem"));
                if (!Arrays.equals(original[1].getTBSCertificate(), chain[1].getTBSCertificate())) {
                    throw new AssertionError("Signed certificate content changed");
                }
                if (variant == 1 && (Arrays.equals(original[1].getEncoded(), chain[1].getEncoded())
                        || Arrays.equals(original[1].getSignature(), chain[1].getSignature())
                        != kind.equals("rsa"))) {
                    throw new AssertionError("Missing representative signature/encoding transformation");
                }
                for (int i = 0; i < chain.length; i++) {
                    chain[i].checkValidity(FIXTURE_DATE);
                    chain[i].verify((i + 1 < chain.length ? chain[i + 1] : root).getPublicKey());
                }
                try {
                    String authType = chain[0].getPublicKey().getAlgorithm().equals("EC")
                            ? "ECDHE_ECDSA" : "ECDHE_RSA";
                    manager.checkServerTrusted(chain, authType);
                    if (blocked) throw new AssertionError("Incident chain accepted: " + kind + variant);
                } catch (CertificateException error) {
                    if (!blocked || !hasMessage(error, "Untrusted certificate")) throw error;
                }
                System.out.println("CHAIN_PASS " + kind + "-" + variant + " blocked=" + blocked);
            }
        }
    }

    private static void tls(KeyStore keys, X509TrustManager manager, String hostname,
                            String expectedError) throws Exception {
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(factory.getKeyManagers(), null, null);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, new TrustManager[]{manager}, null);
        var executor = Executors.newSingleThreadExecutor();
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            var result = executor.submit(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    socket.getOutputStream().write(42);
                } catch (Exception error) {
                    if (expectedError == null) throw new RuntimeException(error);
                }
            });
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), 5000);
                try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
                        .createSocket(raw, hostname, server.getLocalPort(), true)) {
                    client.setSoTimeout(5000);
                    SSLParameters parameters = client.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    client.setSSLParameters(parameters);
                    try {
                        client.startHandshake();
                        if (expectedError != null) throw new AssertionError("Invalid TLS peer accepted");
                        if (client.getInputStream().read() != 42) throw new AssertionError("Missing TLS data");
                    } catch (SSLHandshakeException error) {
                        if (expectedError == null || !hasMessage(error, expectedError)) throw error;
                    }
                }
            }
            result.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        System.out.println("TLS_PASS " + hostname + " expected=" + expectedError);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[2].equals("blocked") || args[2].equals("control"))) {
            throw new IllegalArgumentException("fixtures server.p12 blocked|control");
        }
        if (!System.getProperty("java.specification.version").equals("21")) {
            throw new AssertionError("Java 21 required");
        }
        Path fixtures = Path.of(args[0]);
        incidentChains(fixtures, args[2].equals("blocked"));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(args[1]))) { keys.load(in, PASSWORD); }
        KeyStore anchors = store();
        anchors.setCertificateEntry("ephemeral-control-ca", keys.getCertificate("ca"));
        X509TrustManager trusted = trust(anchors, false);
        tls(keys, trusted, "localhost", null);
        tls(keys, trusted, "wrong-host.invalid", "No subject alternative DNS name matching");
        KeyStore unknown = store();
        unknown.setCertificateEntry("unrelated-root", certificates(fixtures.resolve("rsa-root.pem"))[0]);
        tls(keys, trust(unknown, false), "localhost", "unable to find valid certification path");
        System.out.println("JSSE_REGRESSION_PASS " + System.getProperty("java.runtime.version"));
    }
}
