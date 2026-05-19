FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/app.jar app.jar

ENV SERVER_PORT=7893
ENV DB_URL=jdbc:mysql://mysql:3306/car_agents?useUnicode=true&characterEncoding=UTF-8&serverTimezone=GMT%2B8
ENV DB_USERNAME=root
ENV DB_PASSWORD=root
ENV REDIS_HOST=redis
ENV REDIS_PASSWORD=123456
ENV REDIS_PORT=6379
ENV REDIS_DATABASE=2
ENV LLM_BASE_URL=http://localhost:11434/v1
ENV LLM_API_KEY=sk-placeholder
ENV LLM_AVAILABLE_MODELS=default-model

EXPOSE 7893

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
