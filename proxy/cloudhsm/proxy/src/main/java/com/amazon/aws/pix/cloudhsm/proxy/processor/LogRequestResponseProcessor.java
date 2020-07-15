package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.audit.AuditLog;
import com.amazon.aws.pix.core.util.PixConstants;
import lombok.RequiredArgsConstructor;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.PutRecordResponse;

import java.util.Map;
import java.util.stream.Collectors;

import static com.amazon.aws.pix.cloudhsm.proxy.processor.CaptureRequestProcessor.REQUEST_LOG_PROPERTY;

@RequiredArgsConstructor
public class LogRequestResponseProcessor implements Processor {

    private final FirehoseClient firehoseClient;
    private final String streamName;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> headers = exchange.getIn().getHeaders();

        AuditLog auditLog = (AuditLog) exchange.getProperty(REQUEST_LOG_PROPERTY);
        auditLog.setResponseStatusCode(headers.get("CamelHttpResponseCode"));
        auditLog.setResponseSignatureValid(headers.get(PixConstants.PIX_HEADER_SIGNATURE_VALID));
        auditLog.setResponseBody(exchange.getIn().getBody(String.class));
        auditLog.setResponseHeader(
                headers.entrySet().stream()
                        .filter(e -> !e.getKey().startsWith("Camel"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );

        PutRecordRequest putRecordRequest = PutRecordRequest.builder()
                .deliveryStreamName(streamName)
                .record(builder -> builder.data(SdkBytes.fromUtf8String(auditLog.toJson())))
                .build();

        PutRecordResponse putRecordResponse = firehoseClient.putRecord(putRecordRequest);
    }
}
