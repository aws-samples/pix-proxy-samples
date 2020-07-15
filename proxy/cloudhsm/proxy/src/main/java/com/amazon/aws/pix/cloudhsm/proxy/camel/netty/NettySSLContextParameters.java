package com.amazon.aws.pix.cloudhsm.proxy.camel.netty;

import io.netty.handler.ssl.SslContext;
import lombok.Data;
import org.apache.camel.support.jsse.SSLContextParameters;

@Data
public class NettySSLContextParameters extends SSLContextParameters {

    private SslContext sslContext;

}
