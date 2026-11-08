# The upstream service is an input only; the runtime uses our Admission overlay.
FROM --platform=linux/amd64 crawlercommons/url-frontier@sha256:8fd86c533163bfbcd98006bf8523efe731eea82a87dfe8db1df32a93d1973453 AS frontier
FROM docker:28.5.2-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d AS docker-cli
FROM eclipse-temurin:25-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a
RUN apt-get update && apt-get install -y --no-install-recommends bash curl unzip python3 patch \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/ /usr/local/libexec/docker/cli-plugins/
COPY --from=frontier /home/urlfrontier/urlfrontier-service.jar /opt/frontier/urlfrontier-service.jar
RUN echo 'ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df  /opt/frontier/urlfrontier-service.jar' | sha256sum -c -
ENV URLFRONTIER_BASE_JAR=/opt/frontier/urlfrontier-service.jar
