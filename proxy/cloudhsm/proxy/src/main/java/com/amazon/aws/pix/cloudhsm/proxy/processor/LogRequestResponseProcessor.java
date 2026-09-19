package com.amazon.aws.pix.cloudhsm.proxy.processor;

import com.amazon.aws.pix.core.audit.AuditLog;
import com.amazon.aws.pix.core.util.PixConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.PutRecordResponse;

import java.util.Map;
import java.util.stream.Collectors;

import static com.amazon.aws.pix.cloudhsm.proxy.processor.CaptureRequestProcessor.REQUEST_LOG_PROPERTY;

@Slf4j
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

        // The audit write is the LAST step of the route. Letting it throw fails the whole
        // exchange, so the caller receives an error for a message BACEN may have already
        // accepted - "downstream thinks it failed, upstream already settled", the worst
        // inconsistency for a payment proxy. Audit delivery must therefore not fail the
        // transaction. See upstream issue #18.
        //
        // NOTE FOR PRODUCTION: swallowing the failure trades a correctness problem for a
        // compliance one - a lost audit record. Before going live you MUST decide, in
        // writing and with compliance sign-off, which of the two is acceptable, and add:
        //   1. a durable fallback sink (e.g. CloudWatch Logs) so the record is not lost;
        //   2. an alarm on this log line - an unaudited transaction is a compliance event;
        //   3. ideally, move the write off the critical path entirely (bounded queue +
        //      background PutRecordBatch).
        try {
            PutRecordResponse putRecordResponse = firehoseClient.putRecord(putRecordRequest);
            if (log.isDebugEnabled()) {
                log.debug("audit record delivered, recordId={}", putRecordResponse.recordId());
            }
        } catch (Exception e) {
            log.error("AUDIT DELIVERY FAILED for stream {} - transaction was NOT failed. "
                    + "This is a compliance event: alarm on this line and recover the record.",
                    streamName, e);
        }
    }
}
