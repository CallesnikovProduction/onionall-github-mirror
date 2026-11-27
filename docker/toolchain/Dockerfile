# Rust executables share a pinned compiler. Only binaries enter the toolchain image.
FROM rust:1.93.0-bookworm@sha256:d0a4aa3ca2e1088ac0c81690914a0d810f2eee188197034edf366ed010a2b382 AS rust-toolchain

FROM rust-toolchain AS recovery-build
WORKDIR /source
COPY scripts/recovery/Cargo.toml scripts/recovery/Cargo.lock scripts/recovery/
COPY scripts/recovery/src/ scripts/recovery/src/
RUN cargo build --locked --release --manifest-path scripts/recovery/Cargo.toml

FROM recovery-build AS recovery-test
RUN rustup component add rustfmt clippy
COPY test/m2/onionall_runtime.rs test/m2/onionall_runtime.rs
RUN cargo fmt --manifest-path scripts/recovery/Cargo.toml --check \
    && cargo clippy --locked --manifest-path scripts/recovery/Cargo.toml --all-targets -- -D warnings \
    && cargo test --locked --manifest-path scripts/recovery/Cargo.toml

FROM rust-toolchain AS tooling-build
WORKDIR /source
COPY scripts/tooling/Cargo.toml scripts/tooling/Cargo.lock ./
COPY scripts/tooling/src/ src/
RUN cargo fetch --locked && cargo build --offline --locked --release

FROM tooling-build AS tooling-test
RUN rustup component add rustfmt clippy
RUN --network=none cargo fmt --check \
    && cargo clippy --offline --locked --all-targets -- -D warnings \
    && cargo test --offline --locked

FROM scratch AS tooling-artifact
COPY --from=tooling-test /source/target/release/onionall-tooling /onionall-tooling

FROM rust-toolchain AS content-build
WORKDIR /source
COPY src/native/content/ ./
RUN --network=none cargo build --offline --locked --release

FROM content-build AS content-test
RUN rustup component add rustfmt clippy
RUN --network=none cargo fmt --check \
    && cargo clippy --offline --locked --all-targets -- -D warnings \
    && cargo test --offline --locked

FROM scratch AS content-artifact
COPY --from=content-test /source/target/release/libonionall_content.so /libonionall_content.so

# The upstream service is an input only; the runtime uses our Admission overlay.
FROM --platform=linux/amd64 crawlercommons/url-frontier@sha256:8fd86c533163bfbcd98006bf8523efe731eea82a87dfe8db1df32a93d1973453 AS frontier
FROM docker:28.5.2-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d AS docker-cli
FROM eclipse-temurin:25-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a
RUN apt-get update && apt-get install -y --no-install-recommends bash curl unzip python3 patch gpg gpgv \
    && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/ /usr/local/libexec/docker/cli-plugins/
COPY --from=recovery-build /source/scripts/recovery/target/release/onionall-recovery /usr/local/bin/onionall-recovery
COPY --from=tooling-build /source/target/release/onionall-tooling /usr/local/bin/onionall-tooling
COPY --from=content-build /source/target/release/libonionall_content.so /usr/local/lib/libonionall_content.so
COPY --from=frontier /home/urlfrontier/urlfrontier-service.jar /opt/frontier/urlfrontier-service.jar
RUN echo 'ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df  /opt/frontier/urlfrontier-service.jar' | sha256sum -c -
ENV URLFRONTIER_BASE_JAR=/opt/frontier/urlfrontier-service.jar
ENV ONIONALL_CONTENT_LIBRARY=/usr/local/lib/libonionall_content.so
