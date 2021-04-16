# AWS KMS and AWS Secrets Manager architecture to exemplify digital signature and secure message transmission to the Brazilian Instant Payment System

<p align="center">
  <img src="/images/proxy-kms-arch.png">
</p>

This project contains source code and supporting files that includes the following  folders:

- `proxy/kms` - Proxy that uses AWS KMS.
- `proxy/core` - Sign XML messages.
- `proxy/test` - BACEN simulator.

The main code of application uses several AWS resources, including AWS KMS and AWS Secrets Manager. The audit part of solution use other AWS resources, including [Amazon Kinesis Firehose](https://aws.amazon.com/kinesis/data-firehose/?nc1=h_ls), [Amazon Athena](https://aws.amazon.com/athena/?nc1=h_ls&whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc), [Amazon S3](https://aws.amazon.com/s3/?nc1=h_ls) and [AWS Glue](https://docs.aws.amazon.com/glue/latest/dg/components-overview.html).


## Following is the proposed architecture

The architecture presented here can be part of a more complete, [event-based solution](https://aws.amazon.com/en/event-driven-architecture/), which can cover the entire payment message transmission flow, from the banking core. For example, the complete solution of the Financial Institution (paying or receiving), could contain other complementary architectures such as **Authorization**, **Undo** (based on the [SAGA model](https://docs.aws.amazon.com/whitepapers/latest/microservices-on-aws/distributed-data-management.html)), **Effectiveness**, **Communication with on-premises** environment ([hybrid environment](https://aws.amazon.com/en/hybrid/)), etc., using other services such as [Amazon EventBridge](https://aws.amazon.com/en/eventbridge/), Amazon Simple Notification Service ([SNS](https://aws.amazon.com/en/sns/?whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc)), Amazon Simple Queue Service ([SQS](https://aws.amazon.com/en/sqs/)), [AWS Step Functions](https://aws.amazon.com/en/step-functions/), [Amazon ElastiCache](https://aws.amazon.com/en/elasticache/), [Amazon DynamoDB](https://aws.amazon.com/en/dynamodb/).

In the diagram of our architecture, the green box represents the Proxy of communication with Central Bank, considering the services AWS KMS, AWS Lambda, Amazon API Gateway and AWS Secrets Manager.

The idea of the Proxy is to be a direct and mandatory path for every transaction, with the following objectives:

1. Signature of XML messages.
2. Establishment of the TLS tunnel with mutual authentication (mTLS).
3. Sending the request log to the datastream.


Unlike [AWS CloudHSM architecture](https://aws.amazon.com/blogs/industries/supporting-digital-signature-and-message-transmissions-for-brazilian-instant-payment-system-with-aws-cloudhsm/), in which we use ELB to balance SPI and DICT messages, here [private APIs were used](https://docs.aws.amazon.com/apigateway/latest/developerguide/apigateway-private-apis.html), via AWS API Gateway, to distinguish between the 2 types of messages.

It is worth mentioning that to use AWS KMS, to sign documents with the requirements required by PIX, it is necessary to generate a Certificate Signing Request (CSR) and, subsequently, obtain a valid digital certificate. To learn how to generate a CSR for asymmetric keys managed by AWS KMS, see [here](xxx).

Optionally, the Financial Institution can view the transactions that this solution processes using [Amazon QuickSight](https://aws.amazon.com/quicksight/?nc1=h_ls). QuickSight's serverless architecture allows you to provide insights to everyone in your organization, and you can share interactive and sophisticated dashboards with all your users, allowing them to do detailed searches and explore data to answer questions and gain relevant insights.


<p align="center">
  <img src="/images/proxy-kms-arch1.png" width="600" height="700">
</p>


1. Create the private key (for signature) in AWS KMS.
2. Create the [secret](https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_CreateSecret.html) in [AWS Secrets Manager](https://aws.amazon.com/secrets-manager/?nc1=h_ls) with the content of the private key used for mTLS.
3. Store the 2 certificates in the [AWS Systems Manager Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html): generated key certificate for signature, certificate generated for the mTLS.
4. The Financial Institution Service/Application sends the request in XML format.
5. [Amazon API Gateway](https://aws.amazon.com/api-gateway/?nc1=h_ls) sends the XML message to the application running on [AWS Lambda](https://aws.amazon.com/lambda/?nc1=h_ls).
6. The application (AWS Lambda) uses AWS KMS for digital signature of XML.
7. The application (AWS Lambda) uses the private key stored in AWS Secrets Manager to establish the mTLS.
8. The application transmits the signed XML to BACEN in an encrypted tunnel (mTLS).
9. Application (AWS Lambda) receives the response from BACEN and, if necessary, validates the digital signature of the received XML.
10. Application (AWS Lambda) records the request log sending directly to [Amazon Kinesis Data Firehose](https://aws.amazon.com/kinesis/data-firehose/?nc1=h_ls&kinesis-blogs.sort-by=item.additionalFields.createdDate&kinesis-blogs.sort-order=desc).
11. The reply message is sent to the Amazon API Gateway.
12. The reply message is received by the Service / Application.
13. Amazon Kinesis Data Firehose uses the [AWS Glue Data Catalog](https://aws.amazon.com/glue/?nc1=h_ls&whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc) to convert the logs to parquet format.
14. Amazon Kinesis Data Firehose sends the logs to [Amazon S3](https://aws.amazon.com/s3/?nc1=h_ls), already partitioned into “folders” (/year/month/day/hour/).
15. Amazon Athena uses the [AWS Glue Data Catalog](https://docs.aws.amazon.com/athena/latest/ug/glue-athena.html) as a central place to store and retrieve table metadata.
16. [AWS Glue crawlers](https://docs.aws.amazon.com/glue/latest/dg/add-crawler.html) automatically update new partitions in the metadata repository, every hour.
17. You can immediately query the data directly on Amazon S3 using serverless analysis services, such as [Amazon Athena](https://aws.amazon.com/athena/?nc1=h_ls&whats-new-cards.sort-by=item.additionalFields.postDateTime&whats-new-cards.sort-order=desc) (ad hoc with standard SQL) and [Amazon QuickSight](https://aws.amazon.com/quicksight/?nc1=h_ls).



## How to deploy?

Here are the resources you’ll need in order to follow along with the architecture:

* An Amazon Virtual Private Cloud (Amazon VPC) with the following components:
  * Private Subnet: AWS Lambda will run on private subnet
  * VPC Endpoints: AWS KMS, AWS Secrets Manager, AWS Systems Manager, AWS API Gateway, Amazon Kinesis Firehose

* Private Key and Certificate for establish the mTLS. 

### AWS KMS

1. [Create an asymmetric key](https://docs.aws.amazon.com/kms/latest/developerguide/create-keys.html#create-asymmetric-cmk):
   1. **Key usage**: Sign and verify
   2. **Key spec**: RSA 2048
   
2. Generate the Certificate:
   1. Please, follow the instructions described in [aws-samples/aws-kms-jce](https://github.com/aws-samples/aws-kms-jce)

### AWS Secrets Manager

[Create](https://docs.aws.amazon.com/secretsmanager/latest/userguide/tutorials_basic.html) a secret with name `/pix/proxy/kms/MtlsPrivateKey` and value of Private Key in PKCS8 format :
```
-----BEGIN PRIVATE KEY-----
...
-----END PRIVATE KEY-----
```

To convert PKCS8 with OpenSSL (with necessary):
```
openssl pkcs8 -topk8 -inform PEM -in <MTLS.KEY> -out <MTLS_PKCS8.KEY> -nocrypt
```

### Register (log audit)

1. Before you can upload data to Amazon S3, you must [create a bucket](https://docs.aws.amazon.com/AmazonS3/latest/user-guide/create-bucket.html) in one of the AWS Regions to store your data. After you create a bucket, you can upload an unlimited number of data objects to the bucket

2. The AWS Glue Data Catalog contains references to data that is used as sources and targets of your extract, transform, and load (ETL) jobs in AWS Glue. Information in the Data Catalog is stored as metadata tables, where each table specifies a [single data store](https://docs.aws.amazon.com/glue/latest/dg/populate-data-catalog.html).

3. [Define a database](https://docs.aws.amazon.com/glue/latest/dg/populate-data-catalog.html) in your Data Catalog.

4. [Define two tables](https://docs.aws.amazon.com/glue/latest/dg/tables-described.html): SPI and DICT. Both tables need to have the [Columns Structure](https://docs.aws.amazon.com/glue/latest/dg/aws-glue-api-catalog-tables.html#aws-glue-api-catalog-tables-Column) and [Partition Keys](https://docs.aws.amazon.com/glue/latest/dg/tables-described.html#tables-partition), like example below:

```
columns: [
    {name: 'request_date', type: glue.Schema.STRING},
    {name: 'request_method', type: glue.Schema.STRING},
    {name: 'request_path', type: glue.Schema.STRING},
    {name: 'request_header', type: glue.Schema.STRING},
    {name: 'request_body', type: glue.Schema.STRING},
    {name: 'response_status_code', type: glue.Schema.INTEGER},
    {name: 'response_signature_valid', type: glue.Schema.STRING},
    {name: 'response_header', type: glue.Schema.STRING},
    {name: 'response_body', type: glue.Schema.STRING}
],
            
partitionKeys: [
    {name: 'year', type: glue.Schema.STRING},
    {name: 'month', type: glue.Schema.STRING},
    {name: 'day', type: glue.Schema.STRING},
    {name: 'hour', type: glue.Schema.STRING}
]
```

The DICT table must be pointed to the S3 bucket that you created and must have the prefix `log/dict`.
<br/>
The SPI table must be pointed to the S3 bucket that you created and must have the prefix `log/spi`.

5. [Create a crawler](https://docs.aws.amazon.com/glue/latest/dg/add-crawler.html) with target to the created tables (SPI and DICT).

6. Create two Amazon Kinesis Firehose delivery streams: (SPI and DICT) in the AWS Glue Data Catalog to make the conversion in parquet format. Thus, we have the following prefixes and the destination S3 bucket:

  * DICT develivery stream:
    * Deliver to S3 Bucket created
    * Use the Glue DICT table to convert to PARQUET
    * Specify:
```
prefix: log/dict/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/
errorOutputPrefix: error/dict/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/!{firehose:error-output-type}
```

   * SPI develivery stream:
      * Deliver to S3 Bucket created
      * Use the Glue SPI table to convert to PARQUET
      * Specify:
```
prefix: log/spi/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/
errorOutputPrefix: error/spi/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/!{firehose:error-output-type}
```

### AWS Systems Manager Parameter Store

1. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/SignatureKeyId` and value:
```
<KMS_SIGNATURE_KEY_ID>
```

2. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/SignatureCertificate` and value:
```
-----BEGIN CERTIFICATE-----
<SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

3. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/MtlsCertificate` and value:
```
-----BEGIN CERTIFICATE-----
<SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

4. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/DictAuditStream` and value:
```
<FIREHOSE_DICT_DELIVERY_STREAM_NAME>
```

5. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/SpiAuditStream` and value:
```
<FIREHOSE_SPI_DELIVERY_STREAM_NAME>
```

6. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/BcbDictEndpoint` and value:
```
<DICT_ENDPOINT>
```
TO USE THE TEST - SIMULATOR, use:
```
test.pi.rsfn.net.br:8181
```

7. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/BcbSpiEndpoint` and value:
```
<SPI_ENDPOINT>
```
TO USE THE TEST - SIMULATOR, use:
```
test.pi.rsfn.net.br:9191
```

8. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/BcbSignatureCertificate` and value:
```
-----BEGIN CERTIFICATE-----
<BACEN_SIGNATURE_CERTIFICATE>
-----END CERTIFICATE-----
```

TO USE THE TEST - SIMULATOR, use:
```
-----BEGIN CERTIFICATE-----
MIIDnDCCAoSgAwIBAgIEaLkRBjANBgkqhkiG9w0BAQsFADBkMQswCQYDVQQGEwJC
UjELMAkGA1UECBMCREYxETAPBgNVBAcTCEJyYXNpbGlhMQwwCgYDVQQKEwNCQ0Ix
DDAKBgNVBAsTA1BJWDEZMBcGA1UEAwwQKi5waS5yc2ZuLm5ldC5icjAeFw0yMDA3
MDUxMzUwMzVaFw0zMDA3MDMxMzUwMzVaMGQxCzAJBgNVBAYTAkJSMQswCQYDVQQI
EwJERjERMA8GA1UEBxMIQnJhc2lsaWExDDAKBgNVBAoTA0JDQjEMMAoGA1UECxMD
UElYMRkwFwYDVQQDDBAqLnBpLnJzZm4ubmV0LmJyMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAy38YHSwphFKHH49rFbl/caqP/ugD0vD3n6lrGzC9xukG
q81bVYXKBzbVtn8gxCOsUCIktMoZNe6QCUeTGshreohIFKdzV/ZH70eZcCOcGoZX
3evPJuRYIpjjxp0CJbj71EubylavUNpgGjj9v02ezlto94oQN87YR77sDBPBPGeW
CwaYPN8KY0tW8CqrmJXkMsA+pd/1tv3QbBpkUbEgbTvrVTz+9qEUpAg6SeytIulg
icLQrklYPv/Jex4KKcZxAp6SGBrMYmuCViw40qd1SriWk5HYfmMzXSy6DJ7HO5Im
0F1g43XdEWr0hUmUpsFu2JTIO5qGgx9OcQ6Tw74mgQIDAQABo1YwVDAdBgNVHQ4E
FgQUMswECZ5M0yc1aMkxWdNucYsKjU8wMwYDVR0RAQH/BCkwJ4IJbG9jYWxob3N0
ghRob3N0LmRvY2tlci5pbnRlcm5hbIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEA
varWSOwcE2A5sIsJbPHczsDXiVOObfJjVol/JBXPH00A8uZ6hbsWDCNp7XZHjheW
snw9acXzKvi+NY/kCYaSegsUr9O+2BBcGhCN4LI5uITE9s3YZKyl+2rqk93P7EDB
RSitjPXeRm9ANPZCR90h+amZQLNbfiK0Povrv61isFqLfdGXnk9B6tfLB+baeS8f
HhxEM22sd+5yo9rUZOdAGI72SMzgaMT1AJZbVbb3z2ymDByJkgTAsVdkkSNkqwEi
Y3lg6cJ5thj5NdaXWc8wCzG6L85uAVV/7eh0SMJ2ITMJwkrrqtrX47LeNPrtCTy/
B8Um+Ao1f9w4nbxP53d+6w==
-----END CERTIFICATE-----
```

9. [Create](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-su-create.html) a parameter `/pix/proxy/kms/BcbMtlsCertificate` and value:
```
-----BEGIN CERTIFICATE-----
<BACEN_MTLS_CERTIFICATE>
-----END CERTIFICATE-----
```

TO USE THE TEST - SIMULATOR, use:
```
-----BEGIN CERTIFICATE-----
MIIDnDCCAoSgAwIBAgIEBR5HdTANBgkqhkiG9w0BAQsFADBkMQswCQYDVQQGEwJC
UjELMAkGA1UECBMCREYxETAPBgNVBAcTCEJyYXNpbGlhMQwwCgYDVQQKEwNCQ0Ix
DDAKBgNVBAsTA1BJWDEZMBcGA1UEAwwQKi5waS5yc2ZuLm5ldC5icjAeFw0yMDA3
MDUxMzUxMTFaFw0zMDA3MDMxMzUxMTFaMGQxCzAJBgNVBAYTAkJSMQswCQYDVQQI
EwJERjERMA8GA1UEBxMIQnJhc2lsaWExDDAKBgNVBAoTA0JDQjEMMAoGA1UECxMD
UElYMRkwFwYDVQQDDBAqLnBpLnJzZm4ubmV0LmJyMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAztOPl4NGjpvf/d07FHHkbJKC7xRwoBhvTpTQ/vQp9E3v
hUI4fIgvvsXAzaknifMysdtk1BRS3Urk6tiL9ZCKEVcqfTPTdawcBi2AABrBvWYx
jDTk5dK1o8wPcUyWRDMRXiWv7grODR75u5a+s3bZTOWLIDxGpY2cuDSRWK0bT8Zh
of+8cn4yML03A83mqrfri1rahH/WpGzwOPk6+pv2m/VKv6GTS1ADD5xTExO5Zotg
wYAuU/zUVZ007CvHGGVoJ87hbUr8EmW1DsgxcPGWeKZ0SzCZkV88eLaD6sedyg0q
5w1ACRPSK0fRXHpxLkqckJ72hyinr/S/axOvM9bijQIDAQABo1YwVDAdBgNVHQ4E
FgQU+hfJS2Fp0POVlnuOxCsgquNqJocwMwYDVR0RAQH/BCkwJ4IJbG9jYWxob3N0
ghRob3N0LmRvY2tlci5pbnRlcm5hbIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEA
hjIm6Cj35JR1cbKsIlUFlFVfN7/D9Rx2GOq7JtD4SbzTbDyJ3zm/usVMFFNDNuSs
mJhqLpTKmPX9Akp55RSdnLDEs2tDs7rN5Fy5BODwHblnnyflN0oSihnGop0TtEtv
Gw+zWXms4Pm9Vyi3l+UQA3ENJICP3H7iiPyxj0kThjhFnoIn4kqd+/xSp/BBR6JB
1UofthomxU4qcYcb4gWBYdgaGzoUIk3W3iMBzQDQmmiqAgKYhEA24SrOgkwWw0/o
3RNL7mYI5L3tivyrc0/K3/aE0yPhDAMHpC8V7taUwnb4k7rClB0bqF5GmCnWzBis
NAoejbjou87yzYUTY8nRnw==
-----END CERTIFICATE-----
```

### AWS Lambda

1. Generate the deployment package (function.zip)
```
mvn -f proxy/pom.xml -pl core,kms clean package -DskipTests -Pnative -Dnative-image.docker-build=true
```

2. Create Lambda for SPI:
```
runtime: PROVIDED
handler: 'none'
code: function.zip
memorySize: 1024
timeout: 30 sec
vpc: <VPC>
environment variables:
   - DISABLE_SIGNAL_HANDLERS: true
   - PIX_SPI_PROXY: true
```

3. Create Lambda for DICT:
```
runtime: PROVIDED
handler: 'none'
code: function.zip
memorySize: 1024
timeout: 30 sec
vpc: <VPC>
environment variables:
   - DISABLE_SIGNAL_HANDLERS: true
   - PIX_SPI_PROXY: false
```

4. You also need configure the following [permissions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html) to:
- Read the secret (AWS Secrets Manager).
- Read the parameters (AWS Systems Manager Parameter Store).
- Sign documents (AWS KMS).
- Put data (log) into deliver streams (Amazon Kinesis Data Firehose).

### AWS API Gateway

1. Create a **proxy internal** API for SPI. [Check here](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-set-up-simple-proxy.html).
2. Create a **proxy internal** API for DICT. [Check here](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-set-up-simple-proxy.html).

### AWS Fargate (TEST - SIMULATOR)

1. To configure the Amazon ECS using Fargate for testing, use this [procedure](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/getting-started-fargate.html). You can use the test dockerfile `/proxy/test/src/main/docker/Dockerfile`. You also need configure the following [permissions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html) to:

- Read the parameters (AWS Systems Manager Parameter Store).

You have to expose the service using the **INTERNAL** [Network Load Balancer](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/create-network-load-balancer.html).

2. You have to configure a [private hosted zone](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/hosted-zone-private-creating.html) with domain name `rsfn.net.br`. Also, use this [procedure](https://aws.amazon.com/premiumsupport/knowledge-center/route-53-create-alias-records/) to configure an A record for the name `test.pi.rsfn.net.br` and specify the alias for the TEST Network Load Balancer.

### Amazon Athena and Amazon QuickSight

1. Use this [procedure](https://docs.aws.amazon.com/athena/latest/ug/getting-started.html) to use Amazon Athena to query data in the S3 bucket created previously. 

2. Optionally, you can use [Amazon QuickSight](https://docs.aws.amazon.com/quicksight/latest/user/setup-new-quicksight-account.html) that lets you easily create and publish interactive dashboards that include ML Insights. Dashboards can then be accessed from any device, and embedded into your applications, portals, and website.
