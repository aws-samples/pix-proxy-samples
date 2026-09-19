package com.amazon.aws.pix.core.xml;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.keyinfo.X509IssuerSerial;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Slf4j
public class XmlSigner {

    protected final PrivateKey privateKey;
    protected final X509Certificate certificate;

    protected final KeySelector keySelector;

    protected final String xmlDigestMethod;
    protected final String xmlSignatureMethod;
    protected final String canonicalizationMethod;

    public XmlSigner(@NonNull PrivateKey privateKey, @NonNull X509Certificate certificate,
            @NonNull KeyStore trustStore) {
        this.privateKey = privateKey;
        this.certificate = certificate;

        this.keySelector = new X509IssuerSerialKeySelector(trustStore);

        this.xmlDigestMethod = DigestMethod.SHA256;
        this.xmlSignatureMethod = SignatureMethod.RSA_SHA256;
        this.canonicalizationMethod = CanonicalizationMethod.EXCLUSIVE;
    }

    public String sign(@NonNull String xml) {
        ByteArrayOutputStream xmlSigned = sign(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return new String(xmlSigned.toByteArray(), StandardCharsets.UTF_8);
    }

    public byte[] sign(@NonNull byte[] xml) {
        ByteArrayOutputStream xmlSigned = sign(new ByteArrayInputStream(xml));
        return xmlSigned.toByteArray();
    }

    public ByteArrayOutputStream sign(@NonNull InputStream xml) {
        try {
            Document document = getDocument(xml);
            XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance();
            KeyInfo keyInfo = getKeyInfo(signatureFactory);
            List<Reference> references = getReferences(signatureFactory, keyInfo);
            SignedInfo signedInfo = getSignedInfo(signatureFactory, references);
            XMLSignature signature = signatureFactory.newXMLSignature(signedInfo, keyInfo);
            Element signatureEnvelop = getSignatureEnvelop(document);

            signature.sign(getSignContext(signatureFactory, signatureEnvelop));
            return transform(document);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean verify(@NonNull String xml) {
        return verify(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean verify(@NonNull byte[] xml) {
        return verify(new ByteArrayInputStream(xml));
    }

    public boolean verify(@NonNull InputStream xml) {
        try {
            Document document = getDocument(xml);
            Node signatureNode = getNodeByTagNameNS(document, XMLSignature.XMLNS, "Signature");
            if (signatureNode == null) {
                log.error("No Signature found!");
                return false;
            }

            XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance();

            DOMValidateContext validateContext = getValidateContext(signatureFactory, signatureNode);
            XMLSignature signature = signatureFactory.unmarshalXMLSignature(validateContext);

            boolean valid = signature.validate(validateContext);

            if (!valid) {
                StringBuilder error = new StringBuilder();
                error.append("Signature failed core validation!").append(System.lineSeparator());
                boolean validStatus = signature.getSignatureValue().validate(validateContext);
                error.append("signature validation status: ").append(validStatus).append(System.lineSeparator());
                // Always report per-Reference status. The most common real failure is a valid
                // SignatureValue with a mismatching Reference digest (some part of the document
                // changed); the original sample only logged reference detail when the
                // SignatureValue itself failed, i.e. exactly not in that case. See upstream issue #19.
                Iterator<Reference> referenceIterator = signature.getSignedInfo().getReferences().iterator();
                for (int i = 0; referenceIterator.hasNext(); i++) {
                    error.append("ref[").append(i).append("] validity status: ")
                            .append(referenceIterator.next().validate(validateContext))
                            .append(System.lineSeparator());
                }
                log.error(error.toString());
            }

            return valid;
        } catch (Exception e) {
            // A trusted certificate outside its validity period is a CERTIFICATE ROTATION
            // problem, not a signature mismatch. Collapsing it into `false` (as the original
            // sample did) turns every transaction into HTTP 500 flagged as an invalid
            // signature, which reads like an attack or a counterparty outage.
            // The exception is raised lazily from KeySelectorResult#getKey() during
            // validate(), so by the time it reaches here it may be wrapped an arbitrary
            // number of levels deep - hence the cause-chain walk rather than a typed catch.
            // See upstream issue #19.
            CertificateException validityProblem = findCertificateValidityProblem(e);
            if (validityProblem != null) {
                log.error("trusted certificate is outside its validity period - this is a certificate "
                        + "rotation problem, NOT a signature mismatch", validityProblem);
                throw new CertificateValidityException(
                        "trusted certificate outside its validity period", validityProblem);
            }
            log.error("failed to verify signature", e);
            return false;
        }
    }

    /**
     * Walks the cause chain looking for a certificate validity failure
     * ({@link CertificateExpiredException} / {@link CertificateNotYetValidException}).
     *
     * @return the validity exception if present, otherwise {@code null}
     */
    protected static CertificateException findCertificateValidityProblem(Throwable throwable) {
        Throwable cause = throwable;
        // Bounded walk: guards against self-referencing and cyclic cause chains.
        for (int depth = 0; cause != null && depth < 20; depth++) {
            if (cause instanceof CertificateExpiredException || cause instanceof CertificateNotYetValidException) {
                return (CertificateException) cause;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return null;
    }

    /**
     * Raised when a certificate in the trust store is outside its validity period.
     * Deliberately distinct from a {@code false} verification result so that operators can
     * tell "rotate the certificate" apart from "the signature did not match".
     */
    public static class CertificateValidityException extends RuntimeException {
        public CertificateValidityException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    protected DOMSignContext getSignContext(XMLSignatureFactory signatureFactory, Element signatureEnvelop) {
        DOMSignContext domSignContext = new DOMSignContext(privateKey, signatureEnvelop);
        domSignContext.putNamespacePrefix(XMLSignature.XMLNS, "ds");
        return domSignContext;
    }

    protected Document getDocument(InputStream xml) throws SAXException, IOException, ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setXIncludeAware(false); // Default false for java 8. Disable XML Inclusions leading to SSRF -
                                     // https://portswigger.net/web-security/xxe/lab-xinclude-attack
        dbf.setExpandEntityReferences(false); // Default true for java 8. Disable expand entity reference nodes leading
                                              // to Billion laughs attack [CWE-776].
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(xml);
    }

    protected KeyInfo getKeyInfo(XMLSignatureFactory signatureFactory) {
        KeyInfoFactory keyInfoFactory = signatureFactory.getKeyInfoFactory();
        // X509IssuerSerial must carry the ISSUER DN of the certificate, not its Subject DN.
        // With a self-signed certificate the two are identical, which is why this was not
        // visible in the original sample's tests; with a CA-issued certificate the emitted
        // KeyInfo would identify the wrong issuer. See upstream issue #15.
        X509IssuerSerial x509IssuerSerial = keyInfoFactory
                .newX509IssuerSerial(certificate.getIssuerX500Principal().getName(), certificate.getSerialNumber());
        X509Data x509Data = keyInfoFactory.newX509Data(Collections.singletonList(x509IssuerSerial));
        return keyInfoFactory.newKeyInfo(Collections.singletonList(x509Data), UUID.randomUUID().toString());
    }

    protected List<Reference> getReferences(XMLSignatureFactory signatureFactory, KeyInfo keyInfo)
            throws InvalidAlgorithmParameterException, NoSuchAlgorithmException {
        return List.of(
                signatureFactory.newReference(
                        "#" + keyInfo.getId(),
                        signatureFactory.newDigestMethod(xmlDigestMethod, null),
                        List.of(
                                signatureFactory.newTransform(canonicalizationMethod, (TransformParameterSpec) null)),
                        null,
                        null),
                signatureFactory.newReference(
                        "", // in this case we are signing the whole document, so the URI of ""
                        signatureFactory.newDigestMethod(xmlDigestMethod, null),
                        List.of(
                                signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                                signatureFactory.newTransform(canonicalizationMethod, (TransformParameterSpec) null)),
                        null,
                        null));
    }

    protected SignedInfo getSignedInfo(XMLSignatureFactory signatureFactory, List<Reference> references)
            throws InvalidAlgorithmParameterException, NoSuchAlgorithmException {
        return signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(canonicalizationMethod, (C14NMethodParameterSpec) null),
                signatureFactory.newSignatureMethod(xmlSignatureMethod, null),
                references);
    }

    protected Element getSignatureEnvelop(Document document) {
        return document.getDocumentElement();
    }

    protected ByteArrayOutputStream transform(Document document) throws TransformerException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer trans = tf.newTransformer();
        trans.transform(new DOMSource(document), new StreamResult(outputStream));
        return outputStream;
    }

    protected Node getNodeByTagNameNS(Document document, String ns, String tagName) {
        NodeList nodeList = document.getElementsByTagNameNS(ns, tagName);
        return nodeList.getLength() > 0 ? nodeList.item(0) : null;
    }

    protected Node getNodeByTagName(Document document, String tagName) {
        NodeList nodeList = document.getElementsByTagName(tagName);
        return nodeList.getLength() > 0 ? nodeList.item(0) : null;
    }

    protected DOMValidateContext getValidateContext(XMLSignatureFactory signatureFactory, Node signatureNode) {
        return new DOMValidateContext(keySelector, signatureNode);
    }

}
