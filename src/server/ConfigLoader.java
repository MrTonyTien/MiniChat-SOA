package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * ConfigLoader — Đọc cấu hình ứng dụng
 *
 * Thứ tự ưu tiên (cao → thấp):
 * 1. Environment Variable (khi deploy Docker/Cloud)
 * 2. File application.properties
 * 3. Giá trị mặc định (hardcode trong code)
 *
 * Ví dụ:
 *   int port = ConfigLoader.getInt("server.port", 6789);
 *   → Đọc SERVER_PORT từ ENV, nếu không có thì đọc
 *     server.port từ file, nếu không có thì dùng 6789
 */
public class ConfigLoader {

    private static final Logger log =
            LoggerFactory.getLogger(ConfigLoader.class);

    // Lưu toàn bộ cấu hình từ file properties
    private static final Properties props = new Properties();

    /*
     * Khối static: chạy MỘT LẦN DUY NHẤT khi class được load
     * Đọc file application.properties và lưu vào props
     */
    static {
        String configFile = "/application.properties";
        try (InputStream is =
                     ConfigLoader.class.getResourceAsStream(configFile)) {

            if (is != null) {
                props.load(is);
                log.info("Config loaded | file={}", configFile);
                log.info("Config entries loaded: {} keys",
                        props.size());
            } else {
                log.warn("Config file not found: {}", configFile);
                log.warn("All settings will use default values");
            }

        } catch (IOException e) {
            log.error("Failed to load config file | error={}",
                    e.getMessage());
        }
    }

    /**
     * Đọc cấu hình kiểu số nguyên (int)
     *
     * @param key          Tên cấu hình, ví dụ "server.port"
     * @param defaultValue Giá trị mặc định nếu không tìm thấy
     * @return Giá trị cấu hình
     */
    public static int getInt(String key, int defaultValue) {
        String value = getRawValue(key);

        if (value != null) {
            try {
                int result = Integer.parseInt(value.trim());
                log.debug("Config int | key={} | value={}", key, result);
                return result;
            } catch (NumberFormatException e) {
                log.error("Invalid int config | key={} | value={} " +
                        "| using default={}", key, value, defaultValue);
            }
        }

        log.debug("Config int default | key={} | default={}",
                key, defaultValue);
        return defaultValue;
    }

    /**
     * Đọc cấu hình kiểu chuỗi (String)
     *
     * @param key          Tên cấu hình, ví dụ "db.url"
     * @param defaultValue Giá trị mặc định nếu không tìm thấy
     * @return Giá trị cấu hình
     */
    public static String getString(String key, String defaultValue) {
        String value = getRawValue(key);

        if (value != null) {
            log.debug("Config string | key={} | value={}",
                    key,
                    // Ẩn password trong log
                    key.contains("password") ? "***" : value);
            return value.trim();
        }

        log.debug("Config string default | key={} | default={}",
                key,
                key.contains("password") ? "***" : defaultValue);
        return defaultValue;
    }

    /**
     * Logic đọc giá trị thực tế:
     * Kiểm tra ENV trước, sau đó mới kiểm tra properties file
     *
     * Ví dụ key = "server.port":
     * → envKey = "SERVER_PORT"
     * → Tìm System.getenv("SERVER_PORT")
     * → Nếu không có, tìm props.getProperty("server.port")
     */
    private static String getRawValue(String key) {
        // Chuyển "server.port" → "SERVER_PORT"
        String envKey = key.toUpperCase().replace('.', '_');

        // Bước 1: Kiểm tra Environment Variable
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            log.debug("Config from ENV | envKey={}", envKey);
            return envValue;
        }

        // Bước 2: Kiểm tra file properties
        String propValue = props.getProperty(key);
        if (propValue != null && !propValue.trim().isEmpty()) {
            return propValue;
        }

        // Không tìm thấy ở đâu cả
        return null;
    }

    /**
     * In ra tất cả cấu hình đang dùng (dùng khi debug)
     * Tự động ẩn các giá trị nhạy cảm như password
     */
    public static void printAllConfigs() {
        log.info("=== CURRENT CONFIGURATION ===");
        log.info("server.port         = {}",
                getInt("server.port", 6789));
        log.info("server.thread.pool  = {}",
                getInt("server.thread.pool.size", 50));
        log.info("db.url              = {}",
                getString("db.url", "N/A"));
        log.info("db.user             = {}",
                getString("db.user", "N/A"));
        log.info("db.password         = ***");
        log.info("client.host         = {}",
                getString("client.host", "localhost"));
        log.info("client.port         = {}",
                getInt("client.port", 6789));
        log.info("==============================");
    }
}
