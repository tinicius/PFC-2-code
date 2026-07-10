FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build
COPY project/algos_java/ ./

RUN find . -name "*.java" -print | xargs javac -d classes 2>&1

RUN echo "Main-Class: Main" > manifest.mf && \
    jar cfm experimento.jar manifest.mf -C classes .

FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache aws-cli

WORKDIR /app
COPY --from=builder /build/experimento.jar ./
COPY datasets/ ./datasets/
COPY aws/instances.txt ./
COPY aws/job_config.env ./
COPY aws/entrypoint.sh ./
RUN chmod +x entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]
