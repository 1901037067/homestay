# 后端镜像：多阶段构建。
#
# 刻意不用 `mvn dependency:go-offline` 先拉依赖做层缓存：那个 goal 会在插件按运行期才解析时
# 失败（比如 flyway-maven-plugin 的驱动），而构建失败比"重下一次依赖"贵得多。
# 毕设部署只跑一次，简单可靠优先。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 国内构建走阿里云镜像，理由见 docker/maven-settings.xml；能直连 Central 时可删这一行
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml ./
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
# 全链路 naive 本地时间（实施计划 7.1）：容器里 JVM 的默认时区必须是本地时区，
# 否则 LocalDateTime.now() 会比数据库慢 8 小时，"今天的单"会算到昨天去
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /build/target/bnb-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
