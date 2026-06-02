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

# 历史消息图片本地缓存目录：app.jar 同级的 cache 目录，以 URL 的 MD5 作为文件名（{md5}.dat + {md5}.mime）
# 应用启动时会自动 mkdirs；声明 VOLUME 后宿主机可挂载，避免容器重启缓存失效。
ENV COPILOT_IMAGE_CACHE_DIR=/app/cache
RUN mkdir -p ${COPILOT_IMAGE_CACHE_DIR}
VOLUME ["/app/cache"]

EXPOSE 7893

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
