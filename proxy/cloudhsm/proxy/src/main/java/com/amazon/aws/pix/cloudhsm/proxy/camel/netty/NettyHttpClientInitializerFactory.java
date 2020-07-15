package com.amazon.aws.pix.cloudhsm.proxy.camel.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.component.netty.ChannelHandlerFactory;
import org.apache.camel.component.netty.ClientInitializerFactory;
import org.apache.camel.component.netty.NettyConfiguration;
import org.apache.camel.component.netty.NettyProducer;
import org.apache.camel.component.netty.http.NettyHttpConfiguration;
import org.apache.camel.component.netty.http.NettyHttpProducer;
import org.apache.camel.component.netty.http.handlers.HttpClientChannelHandler;
import org.apache.camel.component.netty.http.handlers.HttpInboundStreamHandler;
import org.apache.camel.component.netty.http.handlers.HttpOutboundStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class NettyHttpClientInitializerFactory extends ClientInitializerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(NettyHttpClientInitializerFactory.class);
    protected NettyHttpConfiguration configuration;
    private NettyHttpProducer producer;
    private SslContext sslContext;

    public NettyHttpClientInitializerFactory() {
        // default constructor needed
    }

    public NettyHttpClientInitializerFactory(NettyHttpProducer nettyProducer) {
        this.producer = nettyProducer;
        try {
            this.sslContext = createSSLContext(producer);
        } catch (Exception e) {
            throw RuntimeCamelException.wrapRuntimeCamelException(e);
        }

        if (sslContext != null) {
            LOG.info("Created SslContext {}", sslContext);
        }
        configuration = nettyProducer.getConfiguration();
    }

    @Override
    public ClientInitializerFactory createPipelineFactory(NettyProducer nettyProducer) {
        return new NettyHttpClientInitializerFactory((NettyHttpProducer) nettyProducer);
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
        // create a new pipeline
        ChannelPipeline pipeline = channel.pipeline();

        SslHandler sslHandler = configureClientSSLOnDemand(channel);
        if (sslHandler != null) {
            //TODO must close on SSL exception
            //sslHandler.setCloseOnSSLException(true);
            LOG.debug("Client SSL handler configured and added as an interceptor against the ChannelPipeline: {}", sslHandler);
            pipeline.addLast("ssl", sslHandler);
        }

        pipeline.addLast("http", new HttpClientCodec());

        List<ChannelHandler> encoders = producer.getConfiguration().getEncoders();
        for (int x = 0; x < encoders.size(); x++) {
            ChannelHandler encoder = encoders.get(x);
            if (encoder instanceof ChannelHandlerFactory) {
                // use the factory to create a new instance of the channel as it may not be shareable
                encoder = ((ChannelHandlerFactory) encoder).newChannelHandler();
            }
            pipeline.addLast("encoder-" + x, encoder);
        }

        List<ChannelHandler> decoders = producer.getConfiguration().getDecoders();
        for (int x = 0; x < decoders.size(); x++) {
            ChannelHandler decoder = decoders.get(x);
            if (decoder instanceof ChannelHandlerFactory) {
                // use the factory to create a new instance of the channel as it may not be shareable
                decoder = ((ChannelHandlerFactory) decoder).newChannelHandler();
            }
            pipeline.addLast("decoder-" + x, decoder);
        }
        if (configuration.isDisableStreamCache()) {
            pipeline.addLast("inbound-streamer", new HttpInboundStreamHandler());
        }
        pipeline.addLast("aggregator", new HttpObjectAggregator(configuration.getChunkedMaxContentLength()));
        pipeline.addLast("outbound-streamer", new HttpOutboundStreamHandler());

        if (producer.getConfiguration().getRequestTimeout() > 0) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("Using request timeout {} millis", producer.getConfiguration().getRequestTimeout());
            }
            ChannelHandler timeout = new ReadTimeoutHandler(producer.getConfiguration().getRequestTimeout(), TimeUnit.MILLISECONDS);
            pipeline.addLast("timeout", timeout);
        }

        // handler to route Camel messages
        pipeline.addLast("handler", new HttpClientChannelHandler(producer));
    }

    protected SslContext createSSLContext(NettyProducer producer) throws Exception {
        NettyConfiguration configuration = producer.getConfiguration();

        if (!configuration.isSsl()) {
            return null;
        }

        if (configuration.getSslContextParameters() != null) {
            return ((NettySSLContextParameters) configuration.getSslContextParameters()).getSslContext();
        }

        return null;
    }

    protected SslHandler configureClientSSLOnDemand(Channel channel) throws Exception {
        if (!producer.getConfiguration().isSsl()) {
            return null;
        }

        if (producer.getConfiguration().getSslHandler() != null) {
            return producer.getConfiguration().getSslHandler();
        } else if (sslContext != null) {
            URI uri = new URI(producer.getEndpoint().getEndpointUri());
            SSLEngine engine = sslContext.newEngine(channel.alloc(), uri.getHost(), uri.getPort());
            engine.setUseClientMode(true);
            SSLParameters sslParameters = engine.getSSLParameters();
            sslParameters.setServerNames(Arrays.asList(new SNIHostName(uri.getHost())));
            engine.setSSLParameters(sslParameters);
            if (producer.getConfiguration().getSslContextParameters() == null) {
                // just set the enabledProtocols if the SslContextParameter doesn't set
                engine.setEnabledProtocols(producer.getConfiguration().getEnabledProtocols().split(","));
            }
            return new SslHandler(engine);
        }

        return null;
    }

}
