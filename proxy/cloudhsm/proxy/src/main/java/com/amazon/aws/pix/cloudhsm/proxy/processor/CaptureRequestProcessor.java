package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.audit.AuditLog;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.Map;
import java.util.stream.Collectors;

public class CaptureRequestProcessor implements Processor {

    public static final String REQUEST_LOG_PROPERTY = "pix.request.log";

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> headers = exchange.getIn().getHeaders();

        AuditLog auditLog = new AuditLog();
        auditLog.setRequestMethod(headers.get("CamelHttpMethod"));
        auditLog.setRequestPath(headers.get("CamelHttpPath"));
        auditLog.setRequestQuery(headers.get("CamelHttpQuery"));
        auditLog.setRequestBody(exchange.getIn().getBody(String.class));
        auditLog.setRequestHeader(
                headers.entrySet().stream()
                        .filter(e -> !e.getKey().startsWith("Camel"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );

        exchange.setProperty(REQUEST_LOG_PROPERTY, auditLog);
    }
}
