FROM amazoncorretto:11
WORKDIR /work/
COPY proxy/test/src/main/docker/ssl /work/ssl
COPY proxy/test/target/*-runner.jar /work/application.jar

ENV WORK_SSL_DIR=/work/ssl

# set up permissions for user `1001`
RUN chmod 775 /work \
  && chown -R 1001 /work \
  && chmod -R "g+rwX" /work \
  && chown -R 1001:root /work

EXPOSE 8181
EXPOSE 9191
USER 1001

CMD ["java", "-jar", "application.jar"]