FROM eclipse-temurin:21-alpine
WORKDIR /app
COPY target/odc-sync-wrapper-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]
