package com.amazon.aws.pix.core.test.xml;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.Iso20022XmlSigner;
import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.security.auth.x500.X500Principal;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Regression test for the {@code KeyInfo} issuer bug (upstream issue #15).
 *
 * <p>The original sample built {@code <ds:X509IssuerSerial>} from the signing certificate's
 * <em>Subject</em> DN instead of its <em>Issuer</em> DN. That defect is invisible when the
 * signing certificate is self-signed, because then Subject == Issuer - which is exactly what
 * every other test in this repository, the bundled BACEN simulator, and the self-signed
 * certificate flow documented in {@code README-CloudHSM.md} all use.
 *
 * <p>This test therefore uses a fixture with a <strong>two-level chain</strong>
 * ({@code security/ca-signed-chain.p12}: a test CA that issued a leaf certificate), so that
 * Subject != Issuer and the defect becomes observable. It asserts the bug in two independent
 * ways, either of which fails against the unpatched code:
 *
 * <ol>
 *   <li>the emitted {@code <ds:X509IssuerName>} must equal the leaf's <em>issuer</em> DN;</li>
 *   <li>{@link XmlSigner#verify} must accept the signature it just produced - with the bug it
 *       cannot, because {@code X509IssuerSerialKeySelector} looks the certificate up by
 *       issuer + serial and the wrong issuer DN matches nothing in the trust store.</li>
 * </ol>
 */
public class XmlSignerCaIssuedCertificateTest {

    private static final String P12 = "security/ca-signed-chain.p12";
    private static final String ALIAS = "leaf";
    private static final char[] PASSWORD = "secret".toCharArray();

    private final PrivateKey privateKey;
    private final X509Certificate leaf;

    @SneakyThrows
    public XmlSignerCaIssuedCertificateTest() {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(Thread.currentThread().getContextClassLoader().getResourceAsStream(P12), PASSWORD);
        privateKey = (PrivateKey) keyStore.getKey(ALIAS, PASSWORD);
        leaf = (X509Certificate) keyStore.getCertificate(ALIAS);
    }

    /**
     * Guards the fixture itself. If this ever fails, the fixture was regenerated as a
     * self-signed certificate and the other assertions below would silently stop testing
     * anything - which is precisely how the original defect survived.
     */
    @Test
    public void fixtureMustHaveDistinctSubjectAndIssuer() {
        Assert.assertNotNull("private key missing from " + P12, privateKey);
        Assert.assertNotNull("leaf certificate missing from " + P12, leaf);
        Assert.assertNotEquals(
                "fixture must be CA-issued, not self-signed, or this test proves nothing",
                leaf.getSubjectX500Principal(), leaf.getIssuerX500Principal());
    }

    @Test
    @SneakyThrows
    public void keyInfoMustCarryIssuerDnNotSubjectDn() {
        String signed = signSample();

        Document document = parse(signed);
        NodeList issuerNames = document.getElementsByTagNameNS(XMLSignature.XMLNS, "X509IssuerName");
        NodeList serials = document.getElementsByTagNameNS(XMLSignature.XMLNS, "X509SerialNumber");

        Assert.assertEquals("expected exactly one X509IssuerName", 1, issuerNames.getLength());
        Assert.assertEquals("expected exactly one X509SerialNumber", 1, serials.getLength());

        X500Principal emitted = new X500Principal(issuerNames.item(0).getTextContent());

        Assert.assertEquals(
                "X509IssuerSerial must carry the certificate's ISSUER DN (upstream issue #15)",
                leaf.getIssuerX500Principal(), emitted);

        // Belt and braces: the Subject DN must NOT be what was emitted.
        Assert.assertNotEquals(
                "X509IssuerName is carrying the Subject DN - this is the unpatched behaviour",
                leaf.getSubjectX500Principal(), emitted);

        Assert.assertEquals("serial number mismatch",
                leaf.getSerialNumber(), new BigInteger(serials.item(0).getTextContent().trim()));
    }

    /**
     * With the wrong issuer DN in {@code KeyInfo}, {@code X509IssuerSerialKeySelector} cannot
     * resolve the certificate from the trust store (it matches on issuer + serial), so the
     * signer fails to verify its own output once the certificate is CA-issued.
     */
    @Test
    @SneakyThrows
    public void signerMustVerifyItsOwnSignatureWithACaIssuedCertificate() {
        Assert.assertTrue(
                "signer could not verify its own signature with a CA-issued certificate",
                newSigner().verify(signSample()));
    }

    @SneakyThrows
    private XmlSigner newSigner() {
        KeyStore trustStore = KeyStoreUtil.generateTrustStore("test", Collections.singletonList(leaf));
        return new Iso20022XmlSigner(privateKey, leaf, trustStore);
    }

    @SneakyThrows
    private String signSample() {
        String xml = FileUtils.readFileToString(
                new File(Thread.currentThread().getContextClassLoader()
                        .getResource("xml/pacs.008_CONTA_1_msg.xml").getFile()),
                "UTF-8");
        return newSigner().sign(xml);
    }

    @SneakyThrows
    private Document parse(String xml) {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
