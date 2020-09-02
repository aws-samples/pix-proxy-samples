package com.amazon.aws.pix.kms.proxy.config;

import lombok.Getter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import javax.annotation.PostConstruct;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Singleton
public class Config {

    @ConfigProperty(name = "aws.region")
    String regionId;

    @Getter
    Region region;

    @ConfigProperty(name = "pix.spi.proxy")
    Boolean spi;

    private enum Secret {
        MtlsPrivateKey;

        public static final String PATH = "/pix/proxy/kms/";

        public String getSecretId() {
            return String.format("%s%s", PATH, this.name());
        }
    }

    private enum Param {
        MtlsCertificate,
        SignatureKeyId,
        SignatureCertificate,
        BcbMtlsCertificate,
        BcbSignatureCertificate,
        BcbDictEndpoint,
        BcbSpiEndpoint,
        SpiAuditStream,
        DictAuditStream;

        public static final String PATH = "/pix/proxy/kms/";

        public String getParamName() {
            return String.format("%s%s", PATH, this.name());
        }
    }

    private Map<Secret, String> secrets;
    private Map<String, String> parameters;

    @PostConstruct
    void init() {
        region = Region.of(regionId);
        loadSecrets();
        loadParameters();
    }

    public String getMtlsPrivateKey() {
        return secrets.get(Secret.MtlsPrivateKey);
    }

    public String getMtlsCertificate() {
        return getParameter(Param.MtlsCertificate);
    }

    public String getSignatureKeyId() {
        return getParameter(Param.SignatureKeyId);
    }

    public String getSignatureCertificate() {
        return getParameter(Param.SignatureCertificate);
    }

    public String getAuditStream() {
        return spi ? getParameter(Param.SpiAuditStream) : getParameter(Param.DictAuditStream);
    }

    public String getBcbMtlsCertificate() {
        return getParameter(Param.BcbMtlsCertificate);
    }

    public String getBcbSignatureCertificate() {
        return getParameter(Param.BcbSignatureCertificate);
    }

    public String getBcbEndpoint() {
        return spi ? getParameter(Param.BcbSpiEndpoint) : getParameter(Param.BcbDictEndpoint);
    }

    public boolean isIso20022() {
        return spi;
    }

    private void loadSecrets() {
        SecretsManagerClient secretsManagerClient = SecretsManagerClient.builder()
                .region(region)
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        secrets = new HashMap<>();

        GetSecretValueResponse secretValue = secretsManagerClient.getSecretValue(builder -> builder.secretId(Secret.MtlsPrivateKey.getSecretId()));
        secrets.put(Secret.MtlsPrivateKey, secretValue.secretString());
    }

    private void loadParameters() {
        SsmClient ssmClient = SsmClient.builder()
                .region(region)
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        parameters = new HashMap<>();
        String nextToken = null;
        do {
            GetParametersByPathResponse response = ssmClient.getParametersByPath(GetParametersByPathRequest.builder().nextToken(nextToken).path(Param.PATH).recursive(true).build());
            parameters.putAll(response.parameters().stream().collect(Collectors.toMap(Parameter::name, Parameter::value)));
            nextToken = response.nextToken();
        } while (nextToken != null);
    }

    private String getParameter(Param param) {
        return Optional.ofNullable(parameters.get(param.getParamName()))
                .orElseThrow(() -> new IllegalStateException(String.format("Parameter %s not found!", param.getParamName())));
    }

}
