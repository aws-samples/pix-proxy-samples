package com.amazon.aws.pix.kms.proxy.service;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.kms.proxy.config.Config;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import io.quarkus.runtime.Startup;
import lombok.SneakyThrows;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.amazon.aws.pix.core.util.PixConstants.PIX_HEADER_PREFIX;

@Startup
public class Sender {

    private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "date", "expect", "from", "host", "upgrade", "via", "warning");

    private final HttpClient httpClient;
    private final String endpoint;

    public Sender(Config config) {
        httpClient = createHttpClient(config);
        endpoint = config.getBcbEndpoint();
    }

    @SneakyThrows
    private HttpClient createHttpClient(Config config) {
        KeyStore keyStore = KeyStoreUtil.generateKeyStore("pix", config.getMtlsPrivateKey(), config.getMtlsCertificate());
        KeyStore trustStore = KeyStoreUtil.generateTrustStore("bcb", config.getBcbMtlsCertificate());

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, null);

        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

        return HttpClient.newBuilder().sslContext(sslContext).build();
    }

    public APIGatewayProxyResponseEvent send(APIGatewayProxyRequestEvent request) {
        try {
            HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder().uri(new URI(String.format("https://%s/%s", endpoint, request.getPath())));
            setHeaders(request, httpRequestBuilder);
            setMethodAndBody(request, httpRequestBuilder);

            HttpResponse<String> httpResponse = httpClient.send(httpRequestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            return getResponse(httpResponse);

        } catch (Exception e) {
            e.printStackTrace();

            APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
            response.setStatusCode(500);
            response.setBody(e.getMessage());
            return response;
        }
    }

    private void setHeaders(APIGatewayProxyRequestEvent request, HttpRequest.Builder httpRequestBuilder) {
        if (request.getHeaders() != null) {
            request.getMultiValueHeaders().forEach((k, l) -> {
                if (!RESTRICTED_HEADERS.contains(k.toLowerCase())) {
                    l.forEach(v -> httpRequestBuilder.header(k, v));
                }
            });
        }
    }

    private void setMethodAndBody(APIGatewayProxyRequestEvent request, HttpRequest.Builder httpRequestBuilder) {
        HttpRequest.BodyPublisher bodyPublisher;
        if (isNotBlank(request.getBody())) {
            bodyPublisher = HttpRequest.BodyPublishers.ofString(request.getBody());
        } else {
            bodyPublisher = HttpRequest.BodyPublishers.noBody();
        }
        httpRequestBuilder.method(request.getHttpMethod(), bodyPublisher);
    }

    private APIGatewayProxyResponseEvent getResponse(HttpResponse<String> httpResponse) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();

        response.setStatusCode(httpResponse.statusCode());
        response.setBody(httpResponse.body());
        response.setHeaders(
                httpResponse.headers().map().entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey(), e -> String.join(", ", e.getValue())))
        );

        Map<String, String> pixRequestHeaders = httpResponse.request().headers().map().entrySet().stream()
                .filter(e -> e.getKey().startsWith(PIX_HEADER_PREFIX))
                .collect(Collectors.toMap(e -> e.getKey(), e -> String.join(", ", e.getValue())));

        response.getHeaders().putAll(pixRequestHeaders);

        return response;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }


}
