package com.amazon.aws.pix.core.xml;

import com.sun.org.apache.xml.internal.security.signature.XMLSignatureInput;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import org.jcp.xml.dsig.internal.dom.ApacheNodeSetData;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.crypto.*;

@AllArgsConstructor
public class Iso20022URIDereferencer implements URIDereferencer {

    public static final String DOCUMENT = "Document";
    public static final String APP_HDR = "AppHdr";
    public static final String APP_HDR_URI = "";

    @NonNull
    private final Document document;

    @NonNull
    private final URIDereferencer defaultDereferencer;

    @Override
    public Data dereference(@NonNull URIReference uriRef, @NonNull XMLCryptoContext context) throws URIReferenceException {
        if (uriRef.getURI() == null) {
            return getData(context, DOCUMENT);
        } else if (uriRef.getURI().equals(APP_HDR_URI)) {
            return getData(context, APP_HDR);
        } else {
            return defaultDereferencer.dereference(uriRef, context);
        }
    }

    private Data getData(XMLCryptoContext context, String element) throws URIReferenceException {
        Node node = getNode(element);
        XMLSignatureInput result = new XMLSignatureInput(node);
        result.setSecureValidation(secureValidation(context));
        result.setExcludeComments(true);
        result.setMIMEType("text/xml");
        return new ApacheNodeSetData(result);
    }

    private Node getNode(String tagName) throws URIReferenceException {
        NodeList nodeList = document.getElementsByTagName(tagName);
        if (nodeList.getLength() == 0) {
            throw new URIReferenceException("No <" + tagName + "> Element detected");
        } else if (nodeList.getLength() > 1) {
            throw new URIReferenceException("Multiple <" + tagName + "> Elements detected");
        }
        return nodeList.item(0);
    }

    private boolean secureValidation(XMLCryptoContext xmlCryptoContext) {
        return xmlCryptoContext == null ? false : getBoolean(xmlCryptoContext, "org.jcp.xml.dsig.secureValidation");
    }

    private boolean getBoolean(XMLCryptoContext xmlCryptoContext, String name) {
        Boolean value = (Boolean) xmlCryptoContext.getProperty(name);
        return value != null && value;
    }
}
