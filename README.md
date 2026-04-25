# Mini Chat v2.0 — Java Socket + SOA

> Tiểu luận môn Phát triển Ứng dụng Hướng Dịch vụ (PTUDHDV)  
> Sinh viên: Nguyễn Thành Tiến | MSSV: 470124078  
> GVHD: ThS. Ngô Thanh Huy  
> Trường Đại học Trà Vinh — Khoa KT&CN

---

## Giới thiệu

Ứng dụng Mini Chat thời gian thực xây dựng bằng 
Java Socket theo mô hình Client-Server, áp dụng 
các nguyên lý Kiến trúc Hướng Dịch vụ (SOA).

## Công nghệ sử dụng

| Công nghệ | Mục đích |
|-----------|----------|
| Java 21 + Socket TCP | Lớp giao vận |
| SLF4J + Logback | Logging chuyên nghiệp |
| H2 Database + JDBC | Lưu trữ lịch sử chat |
| Java Swing | Giao diện đồ họa |
| SSL/TLS 1.3 | Bảo mật truyền tải |
| JUnit 5 | Unit Testing |
| Docker | Containerization |

## Kết quả Benchmark

| Clients | Throughput | p99 Latency | Errors |
|---------|-----------|-------------|--------|
| 2 | 3,919 msg/s | 0.1ms | 0 |
| 5 | 10,846 msg/s | 0.4ms | 0 |
| 10 | 32,992 msg/s | 1.5ms | 0 |
| 20 | 22,248 msg/s | 1.1ms | 0 |

## Cấu trúc Project

- `src/protocol/` — MessageProtocol (Service Contract)
- `src/server/` — ChatServer, ClientHandler, SSL Demo
- `src/client/` — ChatClientSwing (Java Swing GUI)
- `test/` — JUnit Unit Tests
- `benchmark_client.py` — Python Benchmark Tool

## Cách chạy

### Chạy Server
Mở Eclipse → ChatServer.java → Run As Java Application

### Chạy Client
Mở Eclipse → ChatClientSwing.java → Run As Java Application

### Chạy Benchmark
python benchmark_client.py

### Chạy với Docker
docker-compose up
