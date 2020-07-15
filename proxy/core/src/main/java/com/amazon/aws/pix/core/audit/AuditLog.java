package com.amazon.aws.pix.core.audit;

import org.json.JSONObject;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

public class AuditLog {

    private final JSONObject json;

    public AuditLog() {
        this.json = new JSONObject();
        json.put("request_date", Instant.now().toString());
    }

    public void setRequestMethod(Object value) {
        put("request_method", value);
    }

    public void setRequestPath(Object value) {
        put("request_path", value);
    }

    public void setRequestQuery(Object value) {
        put("request_query", value);
    }

    public void setRequestHeader(Map<String, ?> value) {
        put("request_header", value);
    }

    public void setRequestBody(Object value) {
        put("request_body", value);
    }

    public void setResponseStatusCode(Object value) {
        put("response_status_code", value);
    }

    public void setResponseSignatureValid(Object value) {
        put("response_signature_valid", value);
    }

    public void setResponseHeader(Map<String, ?> value) {
        put("response_header", value);
    }

    public void setResponseBody(Object value) {
        put("response_body", value);
    }

    public String toJson() {
        return json.toString();
    }

    private void put(String key, Object value) {
        if (value != null) {
            json.put(key, value);
        }
    }

    private void put(String key, Map<String, ?> value) {
        if (value != null) {
            json.put(key, "[" + value.entrySet().stream().map(e -> String.format("%s=%s", e.getKey(), e.getValue())).collect(Collectors.joining(", ")) + "]");
        }
    }

}
