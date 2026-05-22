# Multi-stage Dockerfile. The build stage runs `sbt stage`; the runtime stage
# is a slim JRE that copies the staged universal layout.
#
# Build:
#   docker build -t recletters:dev .
# Or use docker-compose: `docker compose --profile postgres up`.

FROM sbtscala/scala-sbt:eclipse-temurin-21.0.4_7_1.10.6_3.3.3 AS build
WORKDIR /src
COPY build.sbt ./
COPY project ./project
RUN sbt update
COPY app ./app
COPY conf ./conf
COPY public ./public
RUN sbt stage

FROM eclipse-temurin:21-jre-jammy
LABEL maintainer="Pierre Senellart <pierre@senellart.com>"
RUN groupadd --system --gid 1001 recletters \
 && useradd  --system --uid 1001 --gid recletters --shell /usr/sbin/nologin recletters \
 && mkdir -p /etc/recletters /var/lib/recletters \
 && chown recletters:recletters /etc/recletters /var/lib/recletters
COPY --from=build /src/target/universal/stage /opt/recletters
RUN chown -R recletters:recletters /opt/recletters
USER recletters
EXPOSE 9000
ENV JAVA_OPTS=""
ENTRYPOINT ["/opt/recletters/bin/recletters"]
CMD ["-Dconfig.file=/etc/recletters/application.conf", "-Dhttp.port=9000"]
