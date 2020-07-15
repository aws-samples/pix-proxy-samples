package com.amazon.aws.pix.proxy.test;

import com.amazon.aws.pix.core.util.KeyStoreUtil;
import com.amazon.aws.pix.core.xml.Iso20022XmlSigner;
import com.amazon.aws.pix.core.xml.XmlSigner;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.EndpointConsumerBuilder;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.support.jsse.KeyManagersParameters;
import org.apache.camel.support.jsse.KeyStoreParameters;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.apache.camel.support.jsse.TrustManagersParameters;
import org.apache.commons.io.FileUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class PixProxyTestRouteBuilder extends EndpointRouteBuilder {

    @ConfigProperty(name = "aws.default.region")
    String awsDefaultRegion;

    @ConfigProperty(name = "work.ssl.dir")
    String workSslDir;

    enum CloudHsmParam {
        MtlsCertificate,
        SignatureCertificate;

        public String getParamName() {
            return String.format("/pix/proxy/cloudhsm/%s", this.name());
        }
    }

    enum KmsParam {
        MtlsCertificate,
        SignatureCertificate,
        SignatureSelfSignedCertificate;

        public String getParamName() {
            return String.format("/pix/proxy/kms/%s", this.name());
        }
    }

    private Map<String, String> parameters;
    private XmlSigner xmlSigner;
    private Iso20022XmlSigner iso20022XmlSigner;

    @PostConstruct
    void init() throws Exception {
        loadParameters();
        createXmlSigners();
    }

    @Override
    public void configure() throws Exception {
        createSslContext();

        from(bcbEndpoint("0.0.0.0:8181"))
                .transform(body().convertToString())
                .process(new DictProcessor(xmlSigner, workSslDir));

        from(bcbEndpoint("0.0.0.0:9191"))
                .transform(body().convertToString())
                .process(new SpiProcessor(iso20022XmlSigner));

        from(checkEndpoint()).transform(constant("OK"));
    }

    private EndpointConsumerBuilder bcbEndpoint(String endpoint) {
        return nettyHttp(endpoint)
                .matchOnUriPrefix(true)
                .ssl(true)
                .enabledProtocols("TLSv1.2")
                .needClientAuth(true)
                .sslContextParameters("#sslContextParameters")
                .advanced().nativeTransport(true);
    }

    private EndpointConsumerBuilder checkEndpoint() {
        return netty("tcp://0.0.0.0:7171")
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
            GetParametersByPathResponse response = ssmClient.getParametersByPath(GetParametersByPathRequest.builder().nextToken(nextToken).path("/pix/proxy/").recursive(true).build());
            parameters.putAll(response.parameters().stream().collect(Collectors.toMap(Parameter::name, Parameter::value)));
            nextToken = response.nextToken();
        } while (nextToken != null);
    }

    private void createSslContext() throws KeyStoreException, NoSuchAlgorithmException {
        KeyStoreParameters ksp = new KeyStoreParameters();
        ksp.setResource(workSslDir + "/mtls.jks");
        ksp.setPassword("secret");

        KeyManagersParameters kmp = new KeyManagersParameters();
        kmp.setKeyPassword("secret");
        kmp.setKeyStore(ksp);

        List<X509Certificate> trustCertificates = new ArrayList<>();
        Optional.ofNullable(parameters.get(CloudHsmParam.MtlsCertificate.getParamName()))
                .ifPresent(cert -> trustCertificates.addAll(KeyStoreUtil.getCertificates(cert)));
        Optional.ofNullable(parameters.get(KmsParam.MtlsCertificate.getParamName()))
                .ifPresent(cert -> trustCertificates.addAll(KeyStoreUtil.getCertificates(cert)));

        if (trustCertificates.isEmpty()) throw new IllegalStateException("mTLS (CloudHSM/KMS) Certificates not found!");

        KeyStore trustStore = KeyStoreUtil.generateTrustStore("psp", trustCertificates);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        TrustManagersParameters tmp = new TrustManagersParameters();
        tmp.setTrustManager(trustManagerFactory.getTrustManagers()[0]);

        SSLContextParameters sslContextParameters = new SSLContextParameters();
        sslContextParameters.setKeyManagers(kmp);
        sslContextParameters.setTrustManagers(tmp);

        getContext().getRegistry().bind("sslContextParameters", sslContextParameters);
    }

    private void createXmlSigners() throws UnrecoverableEntryException, NoSuchAlgorithmException, KeyStoreException {

        List<X509Certificate> trustCertificates = new ArrayList<>();
        Optional.ofNullable(parameters.get(CloudHsmParam.SignatureCertificate.getParamName()))
                .ifPresent(cert -> trustCertificates.addAll(KeyStoreUtil.getCertificates(cert)));
        Optional.ofNullable(parameters.get(KmsParam.SignatureCertificate.getParamName()))
                .ifPresent(cert -> trustCertificates.addAll(KeyStoreUtil.getCertificates(cert)));
        Optional.ofNullable(parameters.get(KmsParam.SignatureSelfSignedCertificate.getParamName()))
                .ifPresent(cert -> trustCertificates.addAll(KeyStoreUtil.getCertificates(cert)));

        if (trustCertificates.isEmpty())
            throw new IllegalStateException("Signature (CloudHSM/KMS) Certificates not found!");

        KeyStore keyStore = KeyStoreUtil.getKeyStore(new File(workSslDir + "/sig.jks"), "secret");
        KeyStore.PrivateKeyEntry privateKeyStoreEntry = (KeyStore.PrivateKeyEntry) keyStore.getEntry("sig", new KeyStore.PasswordProtection("secret".toCharArray()));
        KeyStore trustStore = KeyStoreUtil.generateTrustStore("psp", trustCertificates);

        this.xmlSigner = new XmlSigner(privateKeyStoreEntry.getPrivateKey(), (X509Certificate) privateKeyStoreEntry.getCertificate(), trustStore);
        this.iso20022XmlSigner = new Iso20022XmlSigner(privateKeyStoreEntry.getPrivateKey(), (X509Certificate) privateKeyStoreEntry.getCertificate(), trustStore);
    }

    @RequiredArgsConstructor
    public static class SpiProcessor implements Processor {

        private final XmlSigner xmlSigner;

        @Override
        public void process(Exchange exchange) throws Exception {
            final String body = exchange.getIn().getBody(String.class);
            if (body != null && body.length() > 0 && !xmlSigner.verify(body)) {
                exchange.getIn().setHeader("CamelHttpResponseCode", 403);
                exchange.getIn().setBody("Signature invalid!");
                return;
            }

            exchange.getIn().setHeader("CamelHttpResponseCode", 201);
            exchange.getIn().setHeader("PI-ResourceId", UUID.randomUUID().toString());
            exchange.getIn().setBody(null);
        }
    }

    public static class DictProcessor implements Processor {

        private final XmlSigner xmlSigner;
        private final String xmlResponse;

        @SneakyThrows
        public DictProcessor(XmlSigner xmlSigner, String workSslDir) {
            this.xmlSigner = xmlSigner;
            this.xmlResponse = FileUtils.readFileToString(new File(workSslDir + "/dict-response.xml"), "UTF-8");
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            final String body = exchange.getIn().getBody(String.class);
            if (body != null && body.length() > 0 && !xmlSigner.verify(body)) {
                exchange.getIn().setHeader("CamelHttpResponseCode", 403);
                exchange.getIn().setBody("Signature invalid!");
                return;
            }

            exchange.getIn().setHeader("CamelHttpResponseCode", 200);
            exchange.getIn().setHeader("Content-Type", "application/xml;charset=utf-8");

            exchange.getIn().setBody(xmlSigner.sign(xmlResponse));
        }
    }
}
