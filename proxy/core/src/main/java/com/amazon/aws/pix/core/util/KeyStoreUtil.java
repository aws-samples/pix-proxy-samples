package com.amazon.aws.pix.core.util;

import lombok.SneakyThrows;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class KeyStoreUtil {

    @SneakyThrows
    public static KeyStore getKeyStore(File keyStore, String keyStorePassword) {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(new FileInputStream(keyStore), keyStorePassword.toCharArray());
        return ks;
    }

    @SneakyThrows
    public static KeyStore getKeyStoreFromResource(String keyStore, String keyStorePassword) {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(Thread.currentThread().getContextClassLoader().getResourceAsStream(keyStore), keyStorePassword.toCharArray());
        return ks;
    }

    @SneakyThrows
    public static KeyStore generateTrustStore(String aliasPrefix, String certificate) {
        return generateTrustStore(aliasPrefix, getCertificates(certificate));
    }

    @SneakyThrows
    public static KeyStore generateTrustStore(String aliasPrefix, File certificate) {
        return generateTrustStore(aliasPrefix, getCertificates(certificate));
    }

    @SneakyThrows
    public static KeyStore generateTrustStore(String aliasPrefix, Collection<X509Certificate> certificates) {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        AtomicInteger index = new AtomicInteger(0);
        Iterator<X509Certificate> certificatesIterator = certificates.iterator();
        while (certificatesIterator.hasNext()) {
            trustStore.setCertificateEntry(aliasPrefix + "-" + index.getAndIncrement(), certificatesIterator.next());
        }
        return trustStore;
    }

    @SneakyThrows
    public static Collection<X509Certificate> getCertificates(String certificate) {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream inputStream = new ByteArrayInputStream(certificate.getBytes(StandardCharsets.UTF_8));
        return (Collection<X509Certificate>) certificateFactory.generateCertificates(inputStream);
    }

    @SneakyThrows
    public static Collection<X509Certificate> getCertificates(File certificate) {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        return (Collection<X509Certificate>) certificateFactory.generateCertificates(new FileInputStream(certificate));
    }

    @SneakyThrows
    public static X509Certificate getCertificate(String certificate) {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream inputStream = new ByteArrayInputStream(certificate.getBytes(StandardCharsets.UTF_8));
        return (X509Certificate) certificateFactory.generateCertificate(inputStream);
    }

    @SneakyThrows
    public static X509Certificate getCertificate(File certificate) {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certificateFactory.generateCertificate(new FileInputStream(certificate));
    }

    @SneakyThrows
    public static X509Certificate getCertificateFromResource(String certificate) {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certificateFactory.generateCertificate(Thread.currentThread().getContextClassLoader().getResourceAsStream(certificate));
    }

}
