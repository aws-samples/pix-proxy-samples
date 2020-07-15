package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.RequiredArgsConstructor;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.Map;

import static com.amazon.aws.pix.core.util.PixConstants.PIX_HEADERS;
import static com.amazon.aws.pix.core.util.PixConstants.PIX_HEADER_SIGNATURE_VALID;

@RequiredArgsConstructor
public class VerifyResponseProcessor implements Processor {

    private final XmlSigner xmlSigner;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> headers = exchange.getIn().getHeaders();
        headers.putAll(exchange.getProperty(PIX_HEADERS, Map.class));

        final String body = exchange.getIn().getBody(String.class);
        if (body != null && body.length() > 0) {
            int statusCode = (int) exchange.getIn().getHeader("CamelHttpResponseCode");
            if (200 <= statusCode && statusCode < 300) {
                final Boolean valid = xmlSigner.verify(body);
                headers.put(PIX_HEADER_SIGNATURE_VALID, valid.toString());
                if (!valid) headers.put("CamelHttpResponseCode", 500);
            }
        }
    }
}
