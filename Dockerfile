# ================================================
# Dockerfile — Mini Chat Server v2.0
# ================================================
# Cách build:
#   docker build -t minichat:v2 .
#
# Cách chạy:
#   docker run -p 6789:6789 minichat:v2
#
# Cách chạy với cấu hình tùy chỉnh:
#   docker run -p 8080:8080 -e SERVER_PORT=8080 minichat:v2
# ================================================

# Dùng Java 21 bản nhẹ (alpine = ~100MB thay vì ~500MB)
FROM eclipse-temurin:21-jre-alpine

# Thêm metadata
LABEL maintainer="Nguyen Thanh Tien <470124078@tvu.edu.vn>"
LABEL version="2.0"
LABEL description="Mini Chat Server - TVU PTUDHDV"

# Thư mục làm việc trong container
WORKDIR /app

# Copy file JAR vào container
# (File này được tạo sau khi build project)
COPY minichat-server.jar app.jar

# Tạo thư mục cần thiết
RUN mkdir -p /app/data /app/logs

# Khai báo các cổng
# 6789: Cổng chính TCP
EXPOSE 6789

# Biến môi trường mặc định
# (Override được bằng: docker run -e SERVER_PORT=8080 ...)
ENV SERVER_PORT=6789
ENV SERVER_THREAD_POOL_SIZE=50
ENV DB_URL=jdbc:h2:/app/data/minichat_db
ENV DB_USER=sa
ENV DB_PASSWORD=

# Mount points cho data và log
# Dữ liệu trong này sẽ không bị xóa khi container restart
VOLUME ["/app/data", "/app/logs"]

# Healthcheck: kiểm tra server có đang chạy không
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD nc -z localhost ${SERVER_PORT} || exit 1

# Lệnh khởi động
ENTRYPOINT ["java", \
    "-Xms128m", \
    "-Xmx512m", \
    "-jar", "app.jar"]