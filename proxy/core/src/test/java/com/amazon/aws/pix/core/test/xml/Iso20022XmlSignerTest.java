package com.amazon.aws.pix.core.test.xml;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.Iso20022XmlSigner;
import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.X509Certificate;

public class Iso20022XmlSignerTest {

    private final KeyStore keyStore;

    private final XmlSigner xmlSigner;

    private final PublicKey publicKey;

    @SneakyThrows
    public Iso20022XmlSignerTest() {
        keyStore = KeyStoreUtil.getKeyStoreFromResource("security/client.jks", "secret");
        KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) keyStore.getEntry("client", new KeyStore.PasswordProtection("secret".toCharArray()));

        publicKey = privateKeyEntry.getCertificate().getPublicKey();

        xmlSigner = new Iso20022XmlSigner(privateKeyEntry.getPrivateKey(), (X509Certificate) privateKeyEntry.getCertificate(), keyStore);
    }

    @Test
    @SneakyThrows
    public void test() {
        String xml = FileUtils.readFileToString(new File(this.getClass().getClassLoader().getResource("xml/pacs.008_CONTA_1_msg.xml").getFile()), "UTF-8");

        System.out.println();
        System.out.println("XML: ");
        System.out.println("----------------------------");
        System.out.println(xml);
        System.out.println("----------------------------");
        System.out.println();

        System.out.println();
        System.out.println("XML Signed: ");
        System.out.println("----------------------------");
        String xmlSigned = xmlSigner.sign(xml);
        System.out.println(xmlSigned);
        System.out.println("----------------------------");
        System.out.println();

        System.out.println();
        System.out.println("XML Signature Validation: ");
        System.out.println("----------------------------");

        long keyStoreTimeStart = System.currentTimeMillis();
        boolean valid = xmlSigner.verify(xmlSigned);
        long keyStoreTime = System.currentTimeMillis() - keyStoreTimeStart;
        System.out.println("KeyStore Time: " + keyStoreTime);
        Assert.assertTrue(valid);

        System.out.println(valid);
        System.out.println("----------------------------");
        System.out.println();

    }

}
