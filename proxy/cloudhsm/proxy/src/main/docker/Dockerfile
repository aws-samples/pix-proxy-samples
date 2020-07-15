FROM amazoncorretto:11

WORKDIR /tmp/
RUN curl https://s3.amazonaws.com/cloudhsmv2-software/CloudHsmClient/EL7/cloudhsm-client-latest.el7.x86_64.rpm -O \
    && curl https://s3.amazonaws.com/cloudhsmv2-software/CloudHsmClient/EL7/cloudhsm-client-jce-latest.el7.x86_64.rpm -O \
    && curl https://s3.amazonaws.com/cloudhsmv2-software/CloudHsmClient/EL7/cloudhsm-client-dyn-latest.el7.x86_64.rpm -O

RUN yum -y install ./cloudhsm-client-latest.el7.x86_64.rpm \
    ./cloudhsm-client-jce-latest.el7.x86_64.rpm \
    awscli openssl apr \
    && yum clean all \
    && rm -rf /var/cache/yum

RUN chown -R 1001 /opt/cloudhsm \
  && chmod -R "g+rwX" /opt/cloudhsm \
  && chown -R 1001:root /opt/cloudhsm

ENV PATH="/opt/cloudhsm/bin:${PATH}"
ENV LD_LIBRARY_PATH="/opt/cloudhsm/lib"

WORKDIR /work/
COPY proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh /work/wrapper_script.sh
COPY proxy/cloudhsm/proxy/target/*-runner.jar /work/application.jar

# set up permissions for user `1001`
RUN chmod 775 /work /work/wrapper_script.sh \
  && chown -R 1001 /work \
  && chmod -R "g+rwX" /work \
  && chown -R 1001:root /work

EXPOSE 8080
EXPOSE 9090
USER 1001

CMD ["./wrapper_script.sh"]