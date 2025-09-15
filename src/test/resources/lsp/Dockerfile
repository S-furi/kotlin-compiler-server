FROM gradle:9-jdk-21-and-24

RUN apt-get update && apt-get install -y curl zip unzip bash \
    && rm -rf /var/lib/apt/lists/*

ENV SDKMAN_DIR="/usr/local/sdkman"
RUN curl -s "https://get.sdkman.io" | bash \
    && bash -c "source $SDKMAN_DIR/bin/sdkman-init.sh && sdk install kotlin" \
    && chmod -R a+rwX $SDKMAN_DIR

ENV PATH="$SDKMAN_DIR/candidates/kotlin/current/bin:$PATH"

COPY lsp-users-projects-root/ /lsp-users-projects-root/
WORKDIR /lsp
COPY server/kotlin-lsp-0.0.1-SNAPSHOT-linux-aarch64.zip .
RUN unzip kotlin-lsp-0.0.1-SNAPSHOT-linux-aarch64.zip \
    && rm kotlin-lsp-0.0.1-SNAPSHOT-linux-aarch64.zip \
    && chmod +x ./kotlin-lsp.sh

EXPOSE 9999
CMD ["./kotlin-lsp.sh", "--multi-client", "--socket", "0.0.0.0:9999"]

