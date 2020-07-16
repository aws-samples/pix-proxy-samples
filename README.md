# Architectures to exemplify digital signature and secure message transmission to the Brazilian Instant Payment System (PIX)

### ***You can clone, change, execute it, but *it should not be used as a basis for building the final integration* of the Financial Institution with BACEN (SPI and DICT).***

This project contains source code and supporting files to exemplify digital signature and secure message transmission to the Brazilian Instant Payment System (PIX).  The architectures represent a **proxy** for communication with Brazilian Central Bank (BACEN). The idea of the proxy is to use **AWS CloudHSM** or **AWS KMS** as a direct and mandatory path for every transaction, with the following objectives:

```bash
- Establish the TLS tunnel with mutual authentication (mTLS).
- Signature of XML messages.
- Sending the request log to the datastream.
```

AWS CloudHSM | AWS KMS  |
:-:|:-:|
<img src="/images/hsm.jpg" width="100" height="100">|<img src="/images/kms.jpg" width="100" height="100">|
[Click here!](/README-CloudHSM.md)|Under construction...|

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This library is licensed under the MIT-0 License. See the LICENSE file.

