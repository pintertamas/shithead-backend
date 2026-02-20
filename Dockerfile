# Build stage
FROM maven:3.9-amazoncorretto-17 AS build
WORKDIR /app
COPY . .
RUN mvn -pl backend clean package -DskipTests

# Run stage
FROM amazoncorretto:17
WORKDIR /app
COPY --from=build /app/backend/target/backend-0.0.1-SNAPSHOT.jar shithead.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "shithead.jar"]