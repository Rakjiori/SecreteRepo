# -------------------------------
# Build Stage
# -------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 전체 레포 복사
COPY . .

# Spring Boot 프로젝트 위치로 이동
WORKDIR /app/sbb

# gradlew 실행 권한 추가 (Windows → Linux 권한 문제 해결)
RUN chmod +x ./gradlew

# jar 빌드
RUN ./gradlew clean build -x test


# -------------------------------
# Run Stage
# -------------------------------
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

# build 이미지에서 jar 파일만 복사
COPY --from=build /app/sbb/build/libs/*.jar app.jar

# container 내부 포트
EXPOSE 8080

# 실행
CMD ["java", "-jar", "app.jar"]
