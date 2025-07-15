FROM eclipse-temurin:21-alpine AS builder

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

FROM eclipse-temurin:21-alpine

WORKDIR /app

COPY --from=builder /app/target/odc-sync-wrapper-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8844

CMD ["java", "-jar", "app.jar"]