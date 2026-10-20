FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /source
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -B -q test package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd -u 10001 -r appuser
COPY --from=build /source/target/authorization-limit-service-0.1.0-SNAPSHOT.jar /app/service.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/service.jar"]
