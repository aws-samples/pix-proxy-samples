package com.amazon.aws.pix.kms.proxy.service;

import com.amazon.aws.pix.core.audit.AuditLog;
import com.amazon.aws.pix.core.util.PixConstants;
import com.amazon.aws.pix.kms.proxy.config.Config;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import io.quarkus.runtime.Startup;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Startup
public class Logger {

    // Fully qualified to avoid any ambiguity with this class's own name.
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Logger.class);

    private final FirehoseClient firehoseClient;
    private final String streamName;

    public Logger(Config config) {
        firehoseClient = FirehoseClient.builder()
                .region(config.getRegion())
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        streamName = config.getAuditStream();
    }

    public void log(APIGatewayProxyRequestEvent request, APIGatewayProxyResponseEvent response) {

        AuditLog auditLog = new AuditLog();

        auditLog.setRequestMethod(request.getHttpMethod());
        auditLog.setRequestPath(request.getPath());
        // The original sample never captured the query string in this architecture at all
        // (AuditLog has the setter, but Logger did not call it), so DICT lookups lost the
        // most meaningful part of the audit record - WHAT was looked up. Related to
        // upstream issue #16.
        auditLog.setRequestQuery(toQueryString(request.getMultiValueQueryStringParameters()));
        auditLog.setRequestBody(request.getBody());
        auditLog.setRequestHeader(flatList(request.getMultiValueHeaders()));

        auditLog.setResponseStatusCode(response.getStatusCode());
        auditLog.setResponseSignatureValid(isSignatureValid(response));
        auditLog.setResponseBody(response.getBody());
        auditLog.setResponseHeader(response.getHeaders());

        PutRecordRequest putRecordRequest = PutRecordRequest.builder()
                .deliveryStreamName(streamName)
                .record(builder -> builder.data(SdkBytes.fromUtf8String(auditLog.toJson())))
                .build();

        // Audit delivery must not fail a transaction BACEN may have already accepted.
        // See upstream issue #18 and the production caveats in LogRequestResponseProcessor.
        try {
            firehoseClient.putRecord(putRecordRequest);
        } catch (Exception e) {
            LOG.error("AUDIT DELIVERY FAILED for stream {} - transaction was NOT failed. "
                    + "This is a compliance event: alarm on this line and recover the record.",
                    streamName, e);
        }
    }

    private Map<String, String> flatList(Map<String, List<String>> map) {
        if (map == null) return null;
        return map.entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey(),
                e -> String.join(", ", e.getValue())
        ));
    }

    /**
     * Renders the query parameters as a {@code k=v&k=v} string.
     * <p>
     * Returned as a String on purpose: the Glue column {@code request_query} is typed
     * STRING, so emitting a nested JSON object here would break the Firehose Parquet
     * conversion. This also keeps the field shape identical to the CloudHSM architecture,
     * where Camel already supplies {@code CamelHttpQuery} as a String.
     */
    private String toQueryString(Map<String, List<String>> map) {
        if (map == null || map.isEmpty()) return null;
        return map.entrySet().stream()
                .flatMap(e -> e.getValue() == null
                        ? java.util.stream.Stream.<String>empty()
                        : e.getValue().stream().map(v -> e.getKey() + "=" + v))
                .collect(Collectors.joining("&"));
    }

    private String isSignatureValid(APIGatewayProxyResponseEvent response) {
        if (response.getHeaders() == null) return null;
        return response.getHeaders().get(PixConstants.PIX_HEADER_SIGNATURE_VALID);
    }

}
