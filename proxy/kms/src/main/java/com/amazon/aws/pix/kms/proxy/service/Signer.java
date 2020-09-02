package com.amazon.aws.pix.kms.proxy.service;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.Iso20022XmlSigner;
import com.amazon.aws.pix.core.xml.XmlSigner;
import com.amazon.aws.pix.kms.proxy.config.Config;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import io.quarkus.runtime.Startup;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.jce.provider.KmsProvider;
import software.amazon.awssdk.services.kms.jce.provider.rsa.KmsRSAKeyFactory;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;

import static com.amazon.aws.pix.core.util.PixConstants.PIX_HEADER_SIGNATURE_VALID;

@Slf4j
@Startup
public class Signer {

    private final XmlSigner xmlSigner;

    public Signer(Config config) {
        KmsClient kmsClient = KmsClient.builder()
                .region(config.getRegion())
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        Security.addProvider(new KmsProvider(kmsClient));

        PrivateKey privateKey = KmsRSAKeyFactory.getPrivateKey(config.getSignatureKeyId());
        X509Certificate certificate = KeyStoreUtil.getCertificate(config.getSignatureCertificate());
        KeyStore trustStore = KeyStoreUtil.generateTrustStore("bcb", config.getBcbSignatureCertificate());

        xmlSigner = config.isIso20022() ? new Iso20022XmlSigner(privateKey, certificate, trustStore) : new XmlSigner(privateKey, certificate, trustStore);
    }

    public void sign(APIGatewayProxyRequestEvent request) {
        if (isNotBlank(request.getBody())) {
            request.setBody(xmlSigner.sign(request.getBody()));
        }
    }

    public void verify(APIGatewayProxyResponseEvent response) {
        if (isSuccessfulResponse(response.getStatusCode()) && isNotBlank(response.getBody())) {
            if (xmlSigner.verify(response.getBody())) {
                response.getHeaders().put(PIX_HEADER_SIGNATURE_VALID, "true");
            } else {
                response.setStatusCode(500);
                response.getHeaders().put(PIX_HEADER_SIGNATURE_VALID, "false");
            }
        }
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isSuccessfulResponse(int statusCode) {
        return 200 <= statusCode && statusCode < 300;
    }

}
