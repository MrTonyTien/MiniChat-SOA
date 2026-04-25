package protocol;

/**
 * MessageProtocol — Hợp đồng giao tiếp (Service Contract)
 *
 * Đây là trái tim của nguyên lý SOA Contract-First.
 * Client và Server KHÔNG gọi hàm trực tiếp của nhau.
 * Họ chỉ giao tiếp qua các chuỗi văn bản định nghĩa ở đây.
 *
 * Cú pháp chuẩn: COMMAND:THAM_SO_1|THAM_SO_2
 *
 * Ví dụ:
 *   LOGIN:UserA
 *   SEND_MSG:PhongJava|Xin chào mọi người!
 *   PRIVATE_MSG:UserB|Chào riêng bạn nhé
 */
public class MessageProtocol {

    // ==================== CÁC LỆNH (COMMANDS) ====================
    /** Đăng nhập hệ thống */
    public static final String LOGIN = "LOGIN";

    /** Tạo phòng chat mới */
    public static final String CREATE_GROUP = "CREATE_GROUP";

    /** Tham gia phòng chat đã có */
    public static final String JOIN_GROUP = "JOIN_GROUP";

    /** Gửi tin nhắn đến cả nhóm (Broadcast) */
    public static final String SEND_MSG = "SEND_MSG";

    /** Gửi tin nhắn riêng tư 1-1 (Unicast) */
    public static final String PRIVATE_MSG = "PRIVATE_MSG";

    /** Xem lịch sử chat của nhóm */
    public static final String GET_HISTORY = "GET_HISTORY";

    /** Thoát khỏi hệ thống */
    public static final String EXIT = "EXIT";

    // ==================== CÁC PHẢN HỒI (RESPONSES) ====================
    /** Phản hồi thành công */
    public static final String OK = "OK";

    /** Phản hồi lỗi */
    public static final String ERROR = "ERROR";

    // ==================== BUILDER METHODS ====================
    // Các phương thức này giúp tạo chuỗi lệnh đúng chuẩn
    // Tránh lỗi typo khi viết tay

    /**
     * Tạo lệnh đăng nhập
     * Kết quả: "LOGIN:UserA"
     */
    public static String buildLogin(String username) {
        return LOGIN + ":" + username;
    }

    /**
     * Tạo lệnh tạo nhóm
     * Kết quả: "CREATE_GROUP:PhongJava"
     */
    public static String buildCreateGroup(String groupName) {
        return CREATE_GROUP + ":" + groupName;
    }

    /**
     * Tạo lệnh tham gia nhóm
     * Kết quả: "JOIN_GROUP:PhongJava"
     */
    public static String buildJoinGroup(String groupName) {
        return JOIN_GROUP + ":" + groupName;
    }

    /**
     * Tạo lệnh gửi tin nhắn nhóm
     * Kết quả: "SEND_MSG:PhongJava|Xin chào!"
     */
    public static String buildSendMsg(String groupName, String content) {
        return SEND_MSG + ":" + groupName + "|" + content;
    }

    /**
     * Tạo lệnh gửi tin nhắn riêng tư
     * Kết quả: "PRIVATE_MSG:UserB|Chào bạn!"
     */
    public static String buildPrivateMsg(String recipient,
                                          String content) {
        return PRIVATE_MSG + ":" + recipient + "|" + content;
    }

    /**
     * Tạo lệnh xem lịch sử
     * Kết quả: "GET_HISTORY:PhongJava"
     */
    public static String buildGetHistory(String groupName) {
        return GET_HISTORY + ":" + groupName;
    }

    // ==================== PARSE METHOD ====================

    /**
     * Phân tích chuỗi lệnh thô thành mảng [command, params]
     *
     * Ví dụ:
     *   "LOGIN:UserA"              → ["LOGIN", "UserA"]
     *   "SEND_MSG:Room|Hello|World" → ["SEND_MSG", "Room|Hello|World"]
     *   "EXIT"                     → ["EXIT", ""]
     *   null hoặc ""               → ["", ""]
     *
     * QUAN TRỌNG: Dùng split(limit=2) để chỉ cắt tại dấu : đầu tiên.
     * Phần còn lại (kể cả có nhiều dấu :) được giữ nguyên trong result[1].
     *
     * @param rawMessage Chuỗi lệnh thô từ client
     * @return Mảng 2 phần tử: [0] = lệnh, [1] = tham số
     */
    public static String[] parse(String rawMessage) {
        // Xử lý null và chuỗi rỗng
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return new String[]{"", ""};
        }

        // Cắt tại dấu : đầu tiên, tối đa 2 phần
        // limit=2 đảm bảo nội dung sau : được giữ nguyên
        String[] parts = rawMessage.split(":", 2);

        if (parts.length == 1) {
            // Không có dấu : → chỉ có command, không có params
            return new String[]{parts[0].trim(), ""};
        }

        return new String[]{parts[0].trim(), parts[1].trim()};
    }
}