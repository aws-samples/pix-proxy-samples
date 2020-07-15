package com.amazon.aws.pix.cloudhsm.proxy;

import com.amazon.aws.pix.cloudhsm.proxy.camel.netty.NettyHttpClientInitializerFactory;
import com.amazon.aws.pix.cloudhsm.proxy.camel.netty.NettySSLContextParameters;
import com.amazon.aws.pix.cloudhsm.proxy.processor.CaptureRequestProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.LogRequestResponseProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.SignRequestProcessor;
import com.amazon.aws.pix.cloudhsm.proxy.processor.VerifyResponseProcessor;
import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.Iso20022XmlSigner;
import com.amazon.aws.pix.core.xml.XmlSigner;
import com.cavium.cfm2.CFM2Exception;
import com.cavium.cfm2.LoginManager;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import lombok.AllArgsConstructor;
import org.apache.camel.builder.EndpointConsumerBuilder;
import org.apache.camel.builder.EndpointProducerBuilder;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONObject;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class PixCloudHSMProxyRouteBuilder extends EndpointRouteBuilder {

    @ConfigProperty(name = "aws.default.region")
    String awsDefaultRegion;

    @AllArgsConstructor
    enum Secret {
        CloudHSMSecret("HSM_USER", "HSM_PASSWORD");

        public final String user;
        public final String password;

        public String getSecretId() {
            return String.format("/pix/proxy/cloudhsm/%s", this.name());
        }
    }

    enum Param {
        CloudHSMClusterId,
        CloudHSMCustomerCA,
        MtlsKeyLabel,
        MtlsCertificate,
        SignatureKeyLabel,
        SignatureCertificate,
        BcbMtlsCertificate,
        BcbSignatureCertificate,
        BcbDictEndpoint,
        BcbSpiEndpoint,
        SpiAuditStream,
        DictAuditStream;

        public static final String PATH = "/pix/proxy/cloudhsm/";

        public String getParamName() {
            return String.format("%s%s", PATH, this.name());
        }
    }

    private Map<String, String> parameters;
    private KeyStore cloudHsmKeyStore;
    private SslContext sslContext;
    private XmlSigner xmlSigner;
    private Iso20022XmlSigner iso20022XmlSigner;
    private FirehoseClient firehoseClient;

    @PostConstruct
    void init() throws Exception {
        loadParameters();
        loadCloudHsmKeyStore();
        createSslContext();
        createXmlSigners();
        createFirehoseClient();
    }

    @Override
    public void configure() throws Exception {
        getContext().getRegistry().bind("nettyHttpClientInitializerFactory", new NettyHttpClientInitializerFactory());

        configure(8080, xmlSigner, getParameter(Param.BcbDictEndpoint), getParameter(Param.DictAuditStream));
        configure(9090, iso20022XmlSigner, getParameter(Param.BcbSpiEndpoint), getParameter(Param.SpiAuditStream));

        from(checkEndpoint()).transform(constant("OK"));
    }

    private void configure(int port, XmlSigner xmlSigner, String endpoint, String streamName) {
        from(proxyEndpoint(port))
                .transform(body().convertToString())
                .process(new SignRequestProcessor(xmlSigner))
                .process(new CaptureRequestProcessor())
                .to(bcbEndpoint(endpoint))
                .transform(body().convertToString())
                .process(new VerifyResponseProcessor(xmlSigner))
                .process(new LogRequestResponseProcessor(firehoseClient, streamName));

    }

    private EndpointConsumerBuilder proxyEndpoint(int port) {
        return nettyHttp(String.format("http://0.0.0.0:%d", port))
                .matchOnUriPrefix(true)
                .advanced().nativeTransport(true);
    }

    private EndpointProducerBuilder bcbEndpoint(String endpoint) {
        NettySSLContextParameters nettySSLContextParameters = new NettySSLContextParameters();
        nettySSLContextParameters.setSslContext(sslContext);

        return nettyHttp("https://" + endpoint)
                .bridgeEndpoint(true)
                .throwExceptionOnFailure(false)
                .ssl(true)
                .enabledProtocols("TLSv1.2")
                .sslContextParameters(nettySSLContextParameters)
                .advanced().nativeTransport(true);
    }

    private EndpointConsumerBuilder checkEndpoint() {
        return nettyHttp("http://0.0.0.0:7070/check")
                .advanced().nativeTransport(true);
    }

    private void loadParameters() {
        SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsDefaultRegion))
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

    private void loadCloudHsmKeyStore() throws IOException, CFM2Exception, KeyStoreException, CertificateException, NoSuchAlgorithmException {

        SecretsManagerClient secretsManagerClient = SecretsManagerClient.builder()
                .region(Region.of(awsDefaultRegion))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();

        GetSecretValueResponse secretValue = secretsManagerClient.getSecretValue(builder -> builder.secretId(Secret.CloudHSMSecret.getSecretId()));
        JSONObject secret = new JSONObject(secretValue.secretString());
        String hsmUser = secret.getString(Secret.CloudHSMSecret.user);
        String hsmPassword = secret.getString(Secret.CloudHSMSecret.password);

        Security.addProvider(new com.cavium.provider.CaviumProvider());
        LoginManager.getInstance().login("PARTITION_1", hsmUser, hsmPassword);
        cloudHsmKeyStore = KeyStore.getInstance("CloudHSM");
        cloudHsmKeyStore.load(null, null);
    }

    private void createSslContext() throws SSLException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException {
        PrivateKey signatureKey = (PrivateKey) cloudHsmKeyStore.getKey(getParameter(Param.MtlsKeyLabel), null);
        Collection<X509Certificate> certificates = KeyStoreUtil.getCertificates(getParameter(Param.MtlsCertificate));
        Collection<X509Certificate> trustCertificates = KeyStoreUtil.getCertificates(getParameter(Param.BcbMtlsCertificate));

        sslContext = SslContextBuilder.forClient()
                .sslProvider(SslProvider.OPENSSL)
                .keyManager(signatureKey, certificates)
                .trustManager(trustCertificates)
                .protocols("TLSv1.2")
                .build();
    }

    private void createXmlSigners() throws UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException {
        PrivateKey signatureKey = (PrivateKey) cloudHsmKeyStore.getKey(getParameter(Param.SignatureKeyLabel), null);
        X509Certificate signatureKeyCertificate = KeyStoreUtil.getCertificate(getParameter(Param.SignatureCertificate));
        KeyStore trustStore = KeyStoreUtil.generateTrustStore("bcb", getParameter(Param.BcbSignatureCertificate));

        xmlSigner = new XmlSigner(signatureKey, signatureKeyCertificate, trustStore);
        iso20022XmlSigner = new Iso20022XmlSigner(signatureKey, signatureKeyCertificate, trustStore);
    }

    private void createFirehoseClient() {
        firehoseClient = FirehoseClient.builder()
                .region(Region.of(awsDefaultRegion))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private String getParameter(Param param) {
        return Optional.ofNullable(parameters.get(param.getParamName()))
                .orElseThrow(() -> new IllegalStateException(String.format("Parameter %s not found!", param.getParamName())));
    }

}
