package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ChatServer — Máy chủ trung tâm của Mini Chat v2.0
 *
 * Cải tiến so với v1.0:
 * - Logging chuyên nghiệp với SLF4J/Logback
 * - Cấu hình đọc từ ConfigLoader (không hardcode)
 * - Graceful shutdown: đợi các luồng hoàn thành trước khi tắt
 */
public class ChatServer {

    private static final Logger log =
            LoggerFactory.getLogger(ChatServer.class);

    // Đọc cấu hình từ ConfigLoader
    private static final int PORT =
            ConfigLoader.getInt("server.port", 6789);

    private static final int THREAD_POOL_SIZE =
            ConfigLoader.getInt("server.thread.pool.size", 50);

    // Lưu trữ dùng chung — thread-safe
    // Key: username, Value: ClientHandler của user đó
    private final ConcurrentHashMap<String, ClientHandler> onlineUsers =
            new ConcurrentHashMap<>();

    // Key: tên nhóm, Value: tập hợp username thành viên
    private final ConcurrentHashMap<String, Set<String>> groups =
            new ConcurrentHashMap<>();

    // Thread Pool quản lý các luồng xử lý client
    private final ExecutorService threadPool =
            Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    /**
     * Khởi động server và bắt đầu lắng nghe kết nối
     */
    public void start() {
        // In cấu hình khi khởi động
        ConfigLoader.printAllConfigs();

        log.info("Starting Mini Chat Server v2.0...");
        log.info("Port={} | MaxThreads={}", PORT, THREAD_POOL_SIZE);

        // Khởi tạo Database ngay khi server bật
        DatabaseManager.getInstance();

        // Đăng ký Shutdown Hook — chạy khi server bị tắt (Ctrl+C)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Stopping server...");
            gracefulShutdown();
        }));

        // Bắt đầu lắng nghe kết nối
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {

            log.info("Server is listening on port {}...", PORT);
            log.info("Waiting for clients to connect...");

            // Vòng lặp vô hạn: chấp nhận kết nối mới liên tục
            while (!Thread.currentThread().isInterrupted()) {

                // accept() chặn ở đây cho đến khi có client kết nối
                Socket clientSocket = serverSocket.accept();

                log.info("New client | remoteAddr={} | " +
                        "currentOnline={}",
                        clientSocket.getInetAddress()
                                    .getHostAddress(),
                        onlineUsers.size());

                // Tạo handler và giao cho Thread Pool xử lý
                // Thread Pool sẽ lấy 1 luồng rảnh để chạy handler này
                ClientHandler handler = new ClientHandler(
                        clientSocket, onlineUsers, groups);

                threadPool.submit(handler);
            }

        } catch (IOException e) {
            log.error("Server error | message={}", e.getMessage(), e);
        } finally {
            gracefulShutdown();
        }
    }

    /**
     * Tắt server một cách an toàn:
     * 1. Không nhận kết nối mới
     * 2. Chờ các luồng đang xử lý hoàn thành
     * 3. Đóng database
     */
    private void gracefulShutdown() {
        log.info("Initiating graceful shutdown...");

        // Dừng nhận task mới, chờ task đang chạy hoàn thành
        threadPool.shutdown();

        try {
            // Chờ tối đa 30 giây
            if (!threadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Forcing shutdown after 30s timeout");
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Đóng database
        DatabaseManager.getInstance().close();

        log.info("Server stopped. Goodbye!");
    }

    public static void main(String[] args) {
        new ChatServer().start();
    }
}