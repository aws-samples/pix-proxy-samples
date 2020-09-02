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

        firehoseClient.putRecord(putRecordRequest);
    }

    private Map<String, String> flatList(Map<String, List<String>> map) {
        if (map == null) return null;
        return map.entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey(),
                e -> String.join(", ", e.getValue())
        ));
    }

    private String isSignatureValid(APIGatewayProxyResponseEvent response) {
        if (response.getHeaders() == null) return null;
        return response.getHeaders().get(PixConstants.PIX_HEADER_SIGNATURE_VALID);
    }

}
