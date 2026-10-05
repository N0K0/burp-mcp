package burp.mcp.util;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * TLS/SSL manager for the MCP server.
 *
 * Two modes:
 *   self_signed — generates an in-memory PKCS12 keystore with a self-signed cert
 *   custom      — loads a user-provided PKCS12 keystore from disk
 */
public class TlsManager {

    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String SIG_ALGORITHM = "SHA256withRSA";
    private static final int VALIDITY_DAYS = 365;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Create an SSLServerSocketFactory from a self-signed certificate
     * generated in memory. The cert CN is set to the bind address.
     */
    public static SSLServerSocketFactory createSelfSigned(String cn, char[] password)
            throws Exception {
        return createSelfSignedContext(cn, password).getServerSocketFactory();
    }

    /**
     * Create an SSLContext with an in-memory self-signed certificate.
     * Shared by the server socket factory above and integration tests
     * that need a local TLS target.
     */
    public static SSLContext createSelfSignedContext(String cn, char[] password)
            throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null, password);

        // Generate key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        kpg.initialize(KEY_SIZE);
        KeyPair kp = kpg.generateKeyPair();

        // Build self-signed certificate using BouncyCastle
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + VALIDITY_DAYS * 86400000L);
        BigInteger serial = new BigInteger(64, new SecureRandom());

        X500Name issuer = new X500Name("CN=" + cn + ", O=Burp MCP, L=Local");
        X500Name subject = issuer; // self-signed

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALGORITHM)
                .build(kp.getPrivate());

        X509CertificateHolder certHolder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certHolder);

        // Store in keystore
        ks.setKeyEntry("burp-mcp", kp.getPrivate(), password,
                new Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());

        return ctx;
    }

    /**
     * Create an SSLServerSocketFactory from a PKCS12 keystore file.
     */
    public static SSLServerSocketFactory createFromKeystore(
            String keystorePath, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            ks.load(fis, password);
        } catch (FileNotFoundException e) {
            throw new Exception("Keystore file not found: " + keystorePath, e);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());

        return ctx.getServerSocketFactory();
    }
}
