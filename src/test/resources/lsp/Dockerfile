FROM openjdk:21-slim
RUN apt-get update && apt-get install -y unzip bash && rm -rf /var/lib/apt/lists/*
WORKDIR /lsp
COPY server/kotlin-lsp.zip .
COPY lsp-users-test-projects-root/ /lsp-users-test-projects-root
RUN unzip kotlin-lsp.zip && rm kotlin-lsp.zip
RUN chmod +x ./kotlin-lsp.sh
EXPOSE 9999
CMD ["./kotlin-lsp.sh", "--multi-client", "--socket", "0.0.0.0:9999"]
