package com.amazon.aws.pix.kms.proxy.sync;

import com.amazon.aws.pix.kms.proxy.service.Logger;
import com.amazon.aws.pix.kms.proxy.service.Sender;
import com.amazon.aws.pix.kms.proxy.service.Signer;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ProxyHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final Signer signer;
    private final Sender sender;
    private final Logger logger;

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        signer.sign(request);
        APIGatewayProxyResponseEvent response = sender.send(request);
        signer.verify(response);
        logger.log(request, response);
        return response;
    }

}
