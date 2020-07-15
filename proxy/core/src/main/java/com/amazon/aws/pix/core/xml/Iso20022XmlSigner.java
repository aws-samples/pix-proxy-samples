package com.amazon.aws.pix.core.xml;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

@Slf4j
public class Iso20022XmlSigner extends XmlSigner {

    public static final String FORMAT = "ISO-20022";
    public static final String SGNTR = "Sgntr";
    public static final String APP_HDR = "AppHdr";
    public static final String APP_HDR_URI = "";
    public static final String DOCUMENT_URI = null;
    public static final String ID_PREFIX_URI = "#";

    public Iso20022XmlSigner(@NonNull PrivateKey privateKey, @NonNull X509Certificate certificate, @NonNull KeyStore trustStore) {
        super(privateKey, certificate, trustStore);
    }

    @Override
    protected List<Reference> getReferences(XMLSignatureFactory signatureFactory, KeyInfo keyInfo) throws InvalidAlgorithmParameterException, NoSuchAlgorithmException {
        return List.of(
                signatureFactory.newReference(
                        ID_PREFIX_URI + keyInfo.getId(),
                        signatureFactory.newDigestMethod(xmlDigestMethod, null),
                        List.of(
                                signatureFactory.newTransform(canonicalizationMethod, (TransformParameterSpec) null)
                        ),
                        null,
                        null
                ),
                signatureFactory.newReference(
                        APP_HDR_URI,
                        signatureFactory.newDigestMethod(xmlDigestMethod, null),
                        List.of(
                                signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                                signatureFactory.newTransform(canonicalizationMethod, (TransformParameterSpec) null)
                        ),
                        null,
                        null
                ),
                signatureFactory.newReference(
                        DOCUMENT_URI,
                        signatureFactory.newDigestMethod(xmlDigestMethod, null),
                        List.of(
                                signatureFactory.newTransform(canonicalizationMethod, (TransformParameterSpec) null)
                        ),
                        null,
                        null
                )
        );
    }

    @Override
    protected Element getSignatureEnvelop(Document document) {
        NodeList nodeList = document.getElementsByTagName(SGNTR);
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node.getParentNode().getNodeName().equals(APP_HDR)) {
                node.getParentNode().removeChild(node);
            }
        }

        Node appHdr = getNodeByTagName(document, APP_HDR);
        if (appHdr == null) throw new IllegalStateException("No <" + APP_HDR + "> Element found");

        Element sgntr = document.createElement(SGNTR);
        appHdr.appendChild(sgntr);

        return sgntr;
    }

    @Override
    protected DOMSignContext getSignContext(XMLSignatureFactory signatureFactory, Element signatureEnvelop) {
        DOMSignContext domSignContext = super.getSignContext(signatureFactory, signatureEnvelop);
        domSignContext.setURIDereferencer(new Iso20022URIDereferencer(signatureEnvelop.getOwnerDocument(), signatureFactory.getURIDereferencer()));
        return domSignContext;
    }

    @Override
    protected DOMValidateContext getValidateContext(XMLSignatureFactory signatureFactory, Node signatureNode) {
        DOMValidateContext validateContext = super.getValidateContext(signatureFactory, signatureNode);
        validateContext.setURIDereferencer(new Iso20022URIDereferencer(signatureNode.getOwnerDocument(), signatureFactory.getURIDereferencer()));
        return validateContext;
    }
}
