package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * DatabaseManager — Quản lý kết nối và thao tác H2 Database
 *
 * Áp dụng mẫu Singleton: chỉ có 1 instance duy nhất
 * trong toàn bộ vòng đời ứng dụng.
 *
 * Tất cả 50 luồng trong Thread Pool đều dùng chung
 * instance này để truy cập database.
 */
public class DatabaseManager {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseManager.class);

    // Đọc cấu hình từ ConfigLoader thay vì hardcode
    private static final String DB_URL =
            ConfigLoader.getString("db.url",
                    "jdbc:h2:./data/minichat_db;AUTO_SERVER=TRUE");

    private static final String DB_USER =
            ConfigLoader.getString("db.user", "sa");

    private static final String DB_PASS =
            ConfigLoader.getString("db.password", "");

    // Instance duy nhất — volatile đảm bảo thread-safe
    private static volatile DatabaseManager instance;

    private Connection connection;

    /**
     * Constructor private — ngăn tạo instance từ bên ngoài
     */
    private DatabaseManager() {
        initConnection();
        createTableIfNotExists();
    }

    /**
     * Lấy instance duy nhất (Singleton thread-safe)
     *
     * Dùng double-checked locking với volatile
     * để tránh tạo ra 2 instance khi có 2 luồng
     * gọi getInstance() cùng lúc lần đầu tiên.
     */
    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager();
                }
            }
        }
        return instance;
    }

    /**
     * Khởi tạo kết nối đến H2 Database
     */
    private void initConnection() {
        try {
            // Nạp driver H2
            Class.forName("org.h2.Driver");

            // Tạo kết nối
            connection = DriverManager.getConnection(
                    DB_URL, DB_USER, DB_PASS);

            log.info("Database connected | url={}", DB_URL);

        } catch (ClassNotFoundException e) {
            log.error("H2 Driver not found. " +
                    "Make sure h2.jar is in classpath | error={}",
                    e.getMessage());
            throw new RuntimeException("H2 Driver not found", e);

        } catch (SQLException e) {
            log.error("Database connection failed | " +
                    "url={} | error={}", DB_URL, e.getMessage());
            throw new RuntimeException("DB connection failed", e);
        }
    }

    /**
     * Tạo bảng MESSAGES nếu chưa tồn tại
     * Chạy khi server khởi động
     */
    private void createTableIfNotExists() {
        String createTable = """
                CREATE TABLE IF NOT EXISTS MESSAGES (
                    id         INT AUTO_INCREMENT PRIMARY KEY,
                    sender     VARCHAR(50)   NOT NULL,
                    group_name VARCHAR(100)  NOT NULL,
                    content    VARCHAR(1000) NOT NULL,
                    msg_type   VARCHAR(10)   NOT NULL,
                    timestamp  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
                )
                """;

        String createIndexGroup =
                "CREATE INDEX IF NOT EXISTS idx_group " +
                "ON MESSAGES(group_name)";

        String createIndexTime =
                "CREATE INDEX IF NOT EXISTS idx_time " +
                "ON MESSAGES(timestamp)";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTable);
            stmt.execute(createIndexGroup);
            stmt.execute(createIndexTime);
            log.info("Table MESSAGES ready with indexes");

        } catch (SQLException e) {
            log.error("Failed to create table | error={}",
                    e.getMessage(), e);
        }
    }

    /**
     * Lưu tin nhắn nhóm vào database
     *
     * @param sender    Tên người gửi
     * @param groupName Tên nhóm
     * @param content   Nội dung tin nhắn
     */
    public synchronized void saveGroupMessage(
            String sender, String groupName, String content) {

        String sql = "INSERT INTO MESSAGES " +
                "(sender, group_name, content, msg_type) " +
                "VALUES (?, ?, ?, 'GROUP')";

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setString(1, sender);
            ps.setString(2, groupName);
            ps.setString(3, content);
            ps.executeUpdate();

            log.debug("Message saved | type=GROUP | " +
                    "sender={} | group={}", sender, groupName);

        } catch (SQLException e) {
            log.error("Failed to save group message | " +
                    "sender={} | group={} | error={}",
                    sender, groupName, e.getMessage(), e);
        }
    }

    /**
     * Lưu tin nhắn riêng tư vào database
     *
     * @param sender    Tên người gửi
     * @param recipient Tên người nhận
     * @param content   Nội dung tin nhắn
     */
    public synchronized void savePrivateMessage(
            String sender, String recipient, String content) {

        // Tên nhóm đặc biệt cho tin nhắn riêng tư
        // Sắp xếp theo alphabet để tránh trùng:
        // UserA→UserB và UserB→UserA có cùng key
        String groupKey;
        if (sender.compareTo(recipient) < 0) {
            groupKey = "PRIVATE_" + sender + "_" + recipient;
        } else {
            groupKey = "PRIVATE_" + recipient + "_" + sender;
        }

        String sql = "INSERT INTO MESSAGES " +
                "(sender, group_name, content, msg_type) " +
                "VALUES (?, ?, ?, 'PRIVATE')";

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setString(1, sender);
            ps.setString(2, groupKey);
            ps.setString(3, content);
            ps.executeUpdate();

            log.debug("Message saved | type=PRIVATE | " +
                    "sender={} | recipient={}",
                    sender, recipient);

        } catch (SQLException e) {
            log.error("Failed to save private message | " +
                    "sender={} | recipient={} | error={}",
                    sender, recipient, e.getMessage(), e);
        }
    }

    /**
     * Lấy lịch sử 50 tin nhắn gần nhất của một nhóm
     *
     * @param groupName Tên nhóm cần lấy lịch sử
     * @return Chuỗi lịch sử chat đã định dạng
     */
    public synchronized String getGroupHistory(String groupName) {
        StringBuilder sb = new StringBuilder();

        String sql = "SELECT sender, content, timestamp " +
                "FROM MESSAGES " +
                "WHERE group_name = ? " +
                "ORDER BY timestamp DESC " +
                "LIMIT 50";

        try (PreparedStatement ps =
                     connection.prepareStatement(sql)) {

            ps.setString(1, groupName);
            ResultSet rs = ps.executeQuery();

            sb.append("=== Lịch sử nhóm [")
              .append(groupName)
              .append("] ===\n");

            int count = 0;
            List<String> messages = new ArrayList<>();

            while (rs.next()) {
                String line = String.format("[%s] %s: %s",
                        rs.getTimestamp("timestamp"),
                        rs.getString("sender"),
                        rs.getString("content"));
                messages.add(line);
                count++;
            }

            if (count == 0) {
                sb.append("(Chưa có tin nhắn nào trong nhóm này)\n");
            } else {
                // Đảo ngược để hiển thị tin cũ trước, mới sau
                for (int i = messages.size() - 1; i >= 0; i--) {
                    sb.append(messages.get(i)).append("\n");
                }
                sb.append("--- Hiển thị ")
                  .append(count)
                  .append(" tin nhắn gần nhất ---\n");
            }

            log.debug("History retrieved | group={} | count={}",
                    groupName, count);

        } catch (SQLException e) {
            log.error("Failed to get history | group={} | error={}",
                    groupName, e.getMessage(), e);
            sb.append("Lỗi truy vấn lịch sử: ")
              .append(e.getMessage())
              .append("\n");
        }

        return sb.toString();
    }

    /**
     * Đóng kết nối database khi server tắt
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("Database connection closed");
            }
        } catch (SQLException e) {
            log.error("Error closing database | error={}",
                    e.getMessage());
        }
    }
}