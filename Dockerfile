# ============================================================
# 构建阶段：Maven 编译 + 导出运行时依赖
# 注意：这里刻意不使用 Spring Boot fat jar。
# 应用启动 MCP 子进程时依赖 System.getProperty("java.class.path")，
# fat jar 下该值只有 app.jar，子进程加载不到 FlightMcpServer。
# 因此镜像采用 classes + libs/* 的展开式布局。
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

RUN mvn -B -ntp -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

# ============================================================
# 运行阶段：只带 JRE、class 文件与依赖 jar
# ============================================================
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd --system --uid 10001 appuser \
    && mkdir -p /app/data /app/.agentscope \
    && chown -R appuser:appuser /app

COPY --from=build /workspace/target/classes /app/classes
COPY --from=build /workspace/target/dependency /app/libs

USER appuser

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    SPRING_DATASOURCE_URL="jdbc:h2:file:/app/data/travel_db"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp '/app/classes:/app/libs/*' com.ycy.travelagent.TravelAgentApplication"]
