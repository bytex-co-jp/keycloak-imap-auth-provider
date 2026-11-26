FROM quay.io/keycloak/keycloak:26.8.1 AS builder
ARG PLUGIN_VERSION=1.0.0
COPY --chown=keycloak:keycloak build/libs/keycloak-imap-auth-provider-${PLUGIN_VERSION}.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
