package com.amazon.aws.pix.core.xml;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;

import javax.security.auth.x500.X500Principal;
import javax.xml.crypto.*;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.keyinfo.X509IssuerSerial;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

@AllArgsConstructor
public class X509IssuerSerialKeySelector extends KeySelector {

    @NonNull
    private final KeyStore keyStore;

    @Override
    public KeySelectorResult select(@NonNull KeyInfo keyInfo, Purpose purpose, AlgorithmMethod algorithmMethod, XMLCryptoContext xmlCryptoContext) throws KeySelectorException {
        try {
            X509Data x509Data = (X509Data) keyInfo.getContent().get(0);
            X509IssuerSerial x509IssuerSerial = (X509IssuerSerial) x509Data.getContent().get(0);
            return () -> getPublicKey(x509IssuerSerial);
        } catch (Exception e) {
            throw new KeySelectorException("Failed to find Certificate", e);
        }
    }

    @SneakyThrows
    private PublicKey getPublicKey(X509IssuerSerial x509IssuerSerial) {
        X509CertSelector x509CertSelector = new X509CertSelector();
        x509CertSelector.setSerialNumber(x509IssuerSerial.getSerialNumber());
        x509CertSelector.setIssuer(new X500Principal(x509IssuerSerial.getIssuerName()).getName());

        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            Certificate certificate = keyStore.getCertificate(aliases.nextElement());
            if (certificate != null && x509CertSelector.match(certificate)) {
                ((X509Certificate) certificate).checkValidity();
                return certificate.getPublicKey();
            }
        }

        throw new KeyStoreException("Certificate is not present in KeyStore");
    }
}
