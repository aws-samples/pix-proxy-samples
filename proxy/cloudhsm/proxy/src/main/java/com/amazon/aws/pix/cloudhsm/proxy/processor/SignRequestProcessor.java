package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.RequiredArgsConstructor;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.Map;
import java.util.stream.Collectors;

import static com.amazon.aws.pix.core.util.PixConstants.PIX_HEADERS;
import static com.amazon.aws.pix.core.util.PixConstants.PIX_HEADER_PREFIX;

@RequiredArgsConstructor
public class SignRequestProcessor implements Processor {

    private final XmlSigner xmlSigner;

    @Override
    public final void process(Exchange exchange) throws Exception {
        final String body = exchange.getIn().getBody(String.class);
        if (body != null && body.length() > 0) {
            final String bodySigned = xmlSigner.sign(body);
            exchange.getIn().setBody(bodySigned);
        }

        exchange.setProperty(
                PIX_HEADERS,
                exchange.getIn().getHeaders().entrySet().stream()
                        .filter(e -> e.getKey().startsWith(PIX_HEADER_PREFIX))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }
}
