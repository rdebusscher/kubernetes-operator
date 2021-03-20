FROM payara/server-node:5.2021.1

ENV PAYARA_DEPLOYMENT_GROUP="dg_app" \
    PAYARA_CONFIG_NAME="dg-config" \
    PAYARA_DAS_HOST="payara-das"

COPY --chown=payara:payara bin/* ${SCRIPT_DIR}

RUN true \
    && echo "AS_ADMIN_PASSWORD=${ADMIN_PASSWORD}" > ${PAYARA_PASSWORD_FILE} \
    && chmod +x ${SCRIPT_DIR}/entrypoint.sh \
    && true

# entrypoint.sh suited for Kubernetes.
ENTRYPOINT "${SCRIPT_DIR}/entrypoint.sh"
