# Debian's authenticated security repository supplies the pinned official Tor daemon.
FROM debian:12.12-slim
ARG TOR_VERSION=0.4.9.11-0+deb12u1
ARG OBFS4_VERSION=0.0.14-1+b4
RUN apt-get update \
    && apt-get install -y --no-install-recommends "tor=${TOR_VERSION}" "obfs4proxy=${OBFS4_VERSION}" python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && install -d -m 0700 -o 1000 -g 1000 /var/lib/tor /var/lib/tor/data /var/lib/tor/hidden_service
COPY docker/m2/torrc.gateway /etc/tor/torrc.gateway
COPY docker/m2/torrc.onion /etc/tor/torrc.onion
COPY docker/m2/health.py /usr/local/lib/tor-health.py
COPY docker/m2/entrypoint.py /usr/local/lib/tor-entrypoint.py
COPY docker/m2/state_init.py /usr/local/lib/tor-state-init.py
USER 1000:1000
ENTRYPOINT ["python3", "/usr/local/lib/tor-entrypoint.py"]
CMD ["-f", "/etc/tor/torrc.gateway"]
HEALTHCHECK --interval=5s --timeout=4s --start-period=10s --retries=72 CMD ["python3", "/usr/local/lib/tor-health.py"]
