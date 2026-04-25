package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.KeyStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSLChatServer — Phiên bản Demo Mini Chat với mã hóa TLS
 *
 * ============================================================
 * MỤC ĐÍCH CỦA CLASS NÀY:
 * ============================================================
 * Minh họa Giai đoạn 2 trong Lộ trình Phát triển:
 * "Thiết lập hàng rào Bảo mật An toàn thông tin"
 *
 * SO SÁNH VỚI ChatServer.java (không có SSL):
 * ┌─────────────────────────────────────────────────────┐
 * │ ChatServer.java (Plain text):                       │
 * │   ServerSocket → chấp nhận kết nối bình thường     │
 * │   Dữ liệu: "LOGIN:UserA" → ai cũng đọc được        │
 * │                                                     │
 * │ SSLChatServer.java (Có mã hóa):                     │
 * │   SSLServerSocket → chấp nhận kết nối có TLS       │
 * │   Dữ liệu: "X#9@kL!..." → không thể đọc được       │
 * └─────────────────────────────────────────────────────┘
 *
 * THAY ĐỔI DUY NHẤT SO VỚI ChatServer:
 *   ServerSocket  →  SSLServerSocket
 *   (Tất cả logic xử lý vẫn dùng ClientHandler cũ)
 *
 * CÁCH CHẠY DEMO:
 *   1. Chạy SSLChatServer.main()
 *   2. Mở cmd, kiểm tra bằng lệnh:
 *      openssl s_client -connect localhost:6790
 *   3. Dùng Wireshark bắt gói tin → thấy dữ liệu bị mã hóa
 * ============================================================
 */
public class SSLChatServer {

    private static final Logger log =
            LoggerFactory.getLogger(SSLChatServer.class);

    // ============================================================
    // CẤU HÌNH — Đọc từ application.properties
    // ============================================================

    /** Cổng SSL riêng để phân biệt với ChatServer thường */
    private static final int SSL_PORT =
            ConfigLoader.getInt("ssl.port", 6790);

    /** Đường dẫn đến file keystore trong classpath */
    private static final String KEYSTORE_PATH =
            ConfigLoader.getString(
                    "ssl.keystore.path", "/minichat.jks");

    /** Mật khẩu keystore */
    private static final String KEYSTORE_PASSWORD =
            ConfigLoader.getString(
                    "ssl.keystore.password", "minichat123");

    /** Số luồng tối đa */
    private static final int THREAD_POOL_SIZE =
            ConfigLoader.getInt("server.thread.pool.size", 50);

    // ============================================================
    // DỮ LIỆU DÙNG CHUNG — Giống hệt ChatServer bình thường
    // ============================================================

    private final ConcurrentHashMap<String, ClientHandler> onlineUsers =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Set<String>> groups =
            new ConcurrentHashMap<>();

    private final ExecutorService threadPool =
            Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    // ============================================================
    // PHƯƠNG THỨC CHÍNH
    // ============================================================

    /**
     * Tạo SSLServerSocket với TLS 1.3
     *
     * Đây là phần KHÁC DUY NHẤT so với ChatServer thường.
     * Thay vì:
     *     new ServerSocket(port)
     * Ta dùng:
     *     sslContext.getServerSocketFactory()
     *               .createServerSocket(port)
     */
    public SSLServerSocket createSSLServerSocket() throws Exception {

        log.info("=== Khởi tạo SSL Server Socket ===");

        // ── BƯỚC 1: Nạp KeyStore (file chứa certificate) ──────────
        log.info("Bước 1/4: Nạp KeyStore từ {}", KEYSTORE_PATH);

        KeyStore keyStore = KeyStore.getInstance("JKS");

        // Tìm file minichat.jks trong classpath
        // (file này nằm trong thư mục resources)
        try (InputStream keystoreStream =
                     SSLChatServer.class
                             .getResourceAsStream(KEYSTORE_PATH)) {

            if (keystoreStream == null) {
                String errorMsg = String.format(
                        "Không tìm thấy file KeyStore: %s\n" +
                        "Hãy chạy lệnh keytool để tạo certificate:\n" +
                        "keytool -genkeypair -alias minichat " +
                        "-keyalg RSA -keysize 2048 " +
                        "-validity 365 " +
                        "-keystore src/resources/minichat.jks " +
                        "-storepass minichat123 " +
                        "-keypass minichat123 " +
                        "-dname \"CN=MiniChat,O=TVU,C=VN\"",
                        KEYSTORE_PATH);
                throw new FileNotFoundException(errorMsg);
            }

            // Nạp keystore với mật khẩu
            keyStore.load(keystoreStream,
                    KEYSTORE_PASSWORD.toCharArray());

            log.info("Bước 1/4: ✅ KeyStore nạp thành công | " +
                    "aliases={}", keyStore.size());
        }

        // ── BƯỚC 2: Tạo KeyManager từ KeyStore ────────────────────
        log.info("Bước 2/4: Tạo KeyManager...");

        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());

        keyManagerFactory.init(keyStore,
                KEYSTORE_PASSWORD.toCharArray());

        log.info("Bước 2/4: ✅ KeyManager sẵn sàng");

        // ── BƯỚC 3: Tạo SSLContext với TLS 1.3 ────────────────────
        log.info("Bước 3/4: Khởi tạo SSLContext với TLS 1.3...");

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(
                keyManagerFactory.getKeyManagers(),
                null,   // TrustManager: null = dùng mặc định
                null    // SecureRandom: null = dùng mặc định
        );

        log.info("Bước 3/4: ✅ SSLContext khởi tạo thành công");

        // ── BƯỚC 4: Tạo SSLServerSocket ───────────────────────────
        log.info("Bước 4/4: Tạo SSLServerSocket tại cổng {}...",
                SSL_PORT);

        SSLServerSocketFactory sslServerSocketFactory =
                sslContext.getServerSocketFactory();

        SSLServerSocket sslServerSocket =
                (SSLServerSocket) sslServerSocketFactory
                        .createServerSocket(SSL_PORT);

        // Chỉ cho phép giao thức TLS mạnh, không dùng SSL cũ
        sslServerSocket.setEnabledProtocols(
                new String[]{"TLSv1.2", "TLSv1.3"});

        log.info("Bước 4/4: ✅ SSLServerSocket sẵn sàng");
        log.info("=================================");
        log.info("SSL Server lắng nghe tại cổng {}", SSL_PORT);
        log.info("Giao thức: TLS 1.2 / TLS 1.3");
        log.info("=================================");

        return sslServerSocket;
    }

    /**
     * Khởi động SSL Server
     *
     * Logic HOÀN TOÀN GIỐNG ChatServer.start()
     * Chỉ khác ở dòng tạo ServerSocket
     */
    public void start() {
        log.info("Khởi động Mini Chat SSL Server v2.0...");
        log.info("Cổng SSL: {}", SSL_PORT);

        // Khởi tạo Database
        DatabaseManager.getInstance();

        try {
            // ✅ ĐIỂM KHÁC DUY NHẤT so với ChatServer:
            // Thay vì: new ServerSocket(PORT)
            // Ta dùng: createSSLServerSocket()
            SSLServerSocket sslServerSocket = createSSLServerSocket();

            log.info("Đang chờ kết nối SSL...");

            while (true) {
                // accept() hoạt động GIỐNG HỆT ServerSocket bình thường
                // Nhưng kết nối được thiết lập qua TLS Handshake tự động
                Socket clientSocket = sslServerSocket.accept();

                log.info("SSL Client kết nối | remoteAddr={}",
                        clientSocket.getInetAddress()
                                    .getHostAddress());

                // Dùng CÙNG ClientHandler với ChatServer bình thường
                // → Chứng minh: SSL chỉ thay đổi tầng Transport
                //               Logic nghiệp vụ không đổi
                ClientHandler handler = new ClientHandler(
                        clientSocket, onlineUsers, groups);

                threadPool.submit(handler);
            }

        } catch (Exception e) {
            log.error("SSL Server lỗi | error={}", e.getMessage(), e);
        } finally {
            threadPool.shutdown();
            DatabaseManager.getInstance().close();
        }
    }

    // ============================================================
    // DEMO ĐƠN GIẢN — Chỉ kiểm tra TLS hoạt động
    // ============================================================

    /**
     * Demo đơn giản: chạy server, nhận 1 kết nối, gửi tin test
     *
     * Dùng để kiểm tra TLS trước khi tích hợp vào hệ thống thật
     */
    public void runSimpleDemo() {
        log.info("=== CHẠY SSL DEMO ĐƠN GIẢN ===");
        log.info("Cổng: {}", SSL_PORT);
        log.info("Test bằng lệnh:");
        log.info("  openssl s_client -connect localhost:{}", SSL_PORT);
        log.info("================================");

        try {
            SSLServerSocket sslServerSocket = createSSLServerSocket();

            log.info("Đang chờ 1 kết nối demo...");

            // Chấp nhận 1 kết nối duy nhất cho demo
            Socket client = sslServerSocket.accept();

            log.info("✅ Client kết nối SSL thành công!");
            log.info("   Remote: {}",
                    client.getInetAddress().getHostAddress());

            // Gửi tin nhắn demo
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(
                            client.getOutputStream(), "UTF-8"),
                    true);

            writer.println("╔════════════════════════════════╗");
            writer.println("║   MINI CHAT SSL DEMO SERVER    ║");
            writer.println("╠════════════════════════════════╣");
            writer.println("║ Kết nối được mã hóa TLS 1.3   ║");
            writer.println("║ Dữ liệu này an toàn!           ║");
            writer.println("║                                ║");
            writer.println("║ Nếu bắt gói tin bằng Wireshark ║");
            writer.println("║ bạn sẽ thấy chuỗi byte         ║");
            writer.println("║ không đọc được.                ║");
            writer.println("╚════════════════════════════════╝");

            // Đọc tin nhắn từ client (nếu có)
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            client.getInputStream(), "UTF-8"));

            log.info("Chờ tin nhắn từ client...");
            String clientMsg = reader.readLine();
            if (clientMsg != null) {
                log.info("Client gửi: {}", clientMsg);
                writer.println("Server nhận được: " + clientMsg);
            }

            // Đóng kết nối demo
            client.close();
            sslServerSocket.close();

            log.info("✅ Demo hoàn tất!");
            log.info("SSL/TLS hoạt động đúng.");

        } catch (FileNotFoundException e) {
            log.error("❌ Không tìm thấy certificate!");
            log.error("   {}", e.getMessage());
            log.error("   → Chạy lệnh keytool để tạo certificate trước");

        } catch (Exception e) {
            log.error("❌ Demo lỗi | error={}", e.getMessage(), e);
        }
    }

    // ============================================================
    // SO SÁNH TRỰC QUAN — In ra để minh họa
    // ============================================================

    /**
     * In ra so sánh giữa plain text và SSL
     * Dùng để chụp ảnh đưa vào báo cáo
     */
    public static void printComparison() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║     SO SÁNH: Plain Text vs SSL/TLS      ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║                                          ║");
        System.out.println("║  KHÔNG CÓ SSL (ChatServer.java):        ║");
        System.out.println("║  Wireshark thấy:                        ║");
        System.out.println("║    LOGIN:UserA                          ║");
        System.out.println("║    SEND_MSG:Room1|Xin chào!             ║");
        System.out.println("║    → Ai cũng đọc được! ❌               ║");
        System.out.println("║                                          ║");
        System.out.println("║  CÓ SSL (SSLChatServer.java):           ║");
        System.out.println("║  Wireshark thấy:                        ║");
        System.out.println("║    17 03 03 00 28 4A 9F B2 ...          ║");
        System.out.println("║    (Chuỗi byte vô nghĩa)                ║");
        System.out.println("║    → Không thể đọc được! ✅             ║");
        System.out.println("║                                          ║");
        System.out.println("║  THAY ĐỔI TRONG CODE:                   ║");
        System.out.println("║    Trước: new ServerSocket(6789)        ║");
        System.out.println("║    Sau:   SSLServerSocket(6790)         ║");
        System.out.println("║    Chỉ 1 dòng thay đổi!                ║");
        System.out.println("║                                          ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();
    }

    // ============================================================
    // MAIN — Điểm khởi động
    // ============================================================

    public static void main(String[] args) {
        SSLChatServer sslServer = new SSLChatServer();

        // In so sánh để chụp ảnh đưa vào báo cáo
        printComparison();

        // Chọn chế độ chạy:
        // - runSimpleDemo(): Test nhanh SSL có hoạt động không
        // - start(): Chạy server đầy đủ với SSL

        System.out.println("Chọn chế độ:");
        System.out.println("1 = Demo đơn giản (test TLS)");
        System.out.println("2 = Chạy server đầy đủ");
        System.out.print("Nhập lựa chọn (mặc định = 1): ");

        try {
            java.util.Scanner scanner =
                    new java.util.Scanner(System.in);
            String choice = scanner.nextLine().trim();

            if ("2".equals(choice)) {
                System.out.println("Khởi động SSL Server đầy đủ...");
                sslServer.start();
            } else {
                System.out.println("Chạy Demo đơn giản...");
                sslServer.runSimpleDemo();
            }

        } catch (Exception e) {
            // Nếu không nhập được (chạy tự động), chạy demo
            sslServer.runSimpleDemo();
        }
    }
}