package server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import protocol.MessageProtocol;

import java.io.*;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClientHandler — Xử lý kết nối của MỘT client cụ thể
 *
 * Mỗi client kết nối vào server sẽ có 1 ClientHandler riêng,
 * chạy trong 1 luồng (thread) riêng biệt.
 *
 * Nguyên lý SOA áp dụng:
 * - Autonomy: Lỗi của client này không ảnh hưởng client khác
 * - Boundary: Giao tiếp qua MessageProtocol, không gọi hàm trực tiếp
 */
public class ClientHandler implements Runnable {

    private static final Logger log =
            LoggerFactory.getLogger(ClientHandler.class);

    // Tham chiếu đến các cấu trúc dữ liệu dùng chung của Server
    private final Socket socket;
    private final ConcurrentHashMap<String, ClientHandler> onlineUsers;
    private final ConcurrentHashMap<String, Set<String>> groups;
    private final DatabaseManager db;

    // Luồng đọc/ghi với client này
    private PrintWriter out;
    private BufferedReader in;

    // Tên đăng nhập (null nếu chưa login)
    private String username;

    // Thời điểm kết nối (để tính thời gian session)
    private final long connectTime = System.currentTimeMillis();

    public ClientHandler(
            Socket socket,
            ConcurrentHashMap<String, ClientHandler> onlineUsers,
            ConcurrentHashMap<String, Set<String>> groups) {

        this.socket = socket;
        this.onlineUsers = onlineUsers;
        this.groups = groups;
        this.db = DatabaseManager.getInstance();
    }

    @Override
    public void run() {
        try {
            // Khởi tạo luồng đọc/ghi với encoding UTF-8
            out = new PrintWriter(
                    new OutputStreamWriter(
                            socket.getOutputStream(), "UTF-8"), true);

            in = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(), "UTF-8"));

            log.info("New connection | remoteAddr={}",
                    socket.getInetAddress().getHostAddress());

            // Hướng dẫn client đăng nhập
            sendToSelf("Chào mừng đến Mini Chat v2.0!");
            sendToSelf("Vui lòng đăng nhập: LOGIN:TênBạn");

            // Vòng lặp chính: đọc và xử lý lệnh từ client
            String rawMessage;
            while ((rawMessage = in.readLine()) != null) {
                handleMessage(rawMessage.trim());
            }

        } catch (IOException e) {
            // Client ngắt kết nối đột ngột — bình thường, không phải lỗi
            if (username != null) {
                log.info("Client disconnected unexpectedly | " +
                        "username={}", username);
            }
        } finally {
            // Luôn dọn dẹp tài nguyên dù có lỗi hay không
            cleanup();
        }
    }

    /**
     * Phân tích lệnh và điều hướng xử lý
     */
    private void handleMessage(String raw) {
        if (raw == null || raw.isEmpty()) return;

        // Dùng MessageProtocol để parse — Contract-First
        String[] parsed = MessageProtocol.parse(raw);
        String command = parsed[0].toUpperCase();
        String params  = parsed[1];

        log.debug("Received command | username={} | cmd={} | params={}",
                username != null ? username : "anonymous",
                command,
                params.length() > 50
                        ? params.substring(0, 50) + "..."
                        : params);

        switch (command) {
            case MessageProtocol.LOGIN        -> handleLogin(params);
            case MessageProtocol.CREATE_GROUP -> handleCreateGroup(params);
            case MessageProtocol.JOIN_GROUP   -> handleJoinGroup(params);
            case MessageProtocol.SEND_MSG     -> handleSendMsg(params);
            case MessageProtocol.PRIVATE_MSG  -> handlePrivateMsg(params);
            case MessageProtocol.GET_HISTORY  -> handleGetHistory(params);
            case MessageProtocol.EXIT         -> handleExit();
            default -> {
                log.warn("Unknown command | username={} | cmd={}",
                        username, command);
                sendToSelf("ERROR: Lệnh không hợp lệ [" + command + "]");
                sendToSelf("Các lệnh hợp lệ: LOGIN, CREATE_GROUP, " +
                        "JOIN_GROUP, SEND_MSG, PRIVATE_MSG, " +
                        "GET_HISTORY, EXIT");
            }
        }
    }

    // ===================== XỬ LÝ TỪNG LỆNH =====================

    private void handleLogin(String params) {
        // Kiểm tra đã đăng nhập chưa
        if (username != null) {
            sendToSelf("ERROR: Bạn đã đăng nhập với tên [" +
                    username + "]");
            return;
        }

        String name = params.trim();

        // Kiểm tra tên không rỗng
        if (name.isEmpty()) {
            sendToSelf("ERROR: Tên đăng nhập không được để trống!");
            sendToSelf("Cú pháp: LOGIN:TênCủaBạn");
            return;
        }

        // Kiểm tra tên hợp lệ (chỉ chữ, số, gạch dưới)
        if (!name.matches("[a-zA-Z0-9_]+")) {
            sendToSelf("ERROR: Tên chỉ được dùng chữ cái, " +
                    "số và dấu gạch dưới (_)");
            return;
        }

        // Kiểm tra tên đã tồn tại chưa
        if (onlineUsers.containsKey(name)) {
            sendToSelf("ERROR: Tên [" + name +
                    "] đã có người dùng. Chọn tên khác!");
            log.warn("Login rejected - duplicate name | " +
                    "requestedName={}", name);
            return;
        }

        // Đăng nhập thành công
        username = name;
        onlineUsers.put(username, this);

        sendToSelf("OK: Chào mừng [" + username +
                "]! Đăng nhập thành công.");
        sendToSelf("Số người online: " + onlineUsers.size());

        // Thông báo cho tất cả người dùng khác
        broadcastSystem("[" + username + "] vừa tham gia hệ thống!");

        log.info("Login success | username={} | " +
                "totalOnline={} | remoteAddr={}",
                username,
                onlineUsers.size(),
                socket.getInetAddress().getHostAddress());
    }

    private void handleCreateGroup(String groupName) {
        if (!checkLoggedIn()) return;

        groupName = groupName.trim();

        if (groupName.isEmpty()) {
            sendToSelf("ERROR: Tên nhóm không được để trống!");
            sendToSelf("Cú pháp: CREATE_GROUP:TênNhóm");
            return;
        }

        // Kiểm tra tên nhóm hợp lệ
        if (!groupName.matches("[a-zA-Z0-9_]+")) {
            sendToSelf("ERROR: Tên nhóm chỉ được dùng " +
                    "chữ cái, số và dấu gạch dưới");
            return;
        }

        // Tạo danh sách thành viên mới
        Set<String> newMembers = ConcurrentHashMap.newKeySet();
        newMembers.add(username);

        // putIfAbsent: ATOMIC — an toàn với đa luồng
        // Trả về null nếu tạo thành công
        // Trả về danh sách cũ nếu nhóm đã tồn tại
        Set<String> existing = groups.putIfAbsent(groupName, newMembers);

        if (existing != null) {
            // Nhóm đã tồn tại
            sendToSelf("ERROR: Nhóm [" + groupName +
                    "] đã tồn tại!");
            sendToSelf("Dùng lệnh JOIN_GROUP:" + groupName +
                    " để tham gia");
            log.warn("Create group failed - exists | " +
                    "groupName={} | requestedBy={}",
                    groupName, username);
            return;
        }

        // Tạo thành công
        sendToSelf("OK: Đã tạo nhóm [" + groupName +
                "] thành công!");
        sendToSelf("Bạn là thành viên đầu tiên.");

        log.info("Group created | groupName={} | createdBy={}",
                groupName, username);
    }

    private void handleJoinGroup(String groupName) {
        if (!checkLoggedIn()) return;

        groupName = groupName.trim();

        if (!groups.containsKey(groupName)) {
            sendToSelf("ERROR: Nhóm [" + groupName +
                    "] không tồn tại!");
            sendToSelf("Dùng lệnh CREATE_GROUP:" + groupName +
                    " để tạo nhóm mới");
            return;
        }

        // Thêm vào nhóm
        groups.get(groupName).add(username);
        sendToSelf("OK: Đã tham gia nhóm [" + groupName + "]!");

        // Thông báo cho thành viên trong nhóm
        broadcastToGroup(groupName,
                "[System] " + username + " đã tham gia nhóm!",
                null);

        log.info("User joined group | username={} | group={}",
                username, groupName);
    }

    private void handleSendMsg(String params) {
        if (!checkLoggedIn()) return;

        // Cắt tại dấu | đầu tiên (limit=2)
        // Đảm bảo nội dung có | vẫn được giữ nguyên
        String[] parts = params.split("\\|", 2);

        if (parts.length < 2 ||
                parts[0].trim().isEmpty() ||
                parts[1].trim().isEmpty()) {
            sendToSelf("ERROR: Cú pháp sai!");
            sendToSelf("Đúng: SEND_MSG:TênNhóm|Nội dung tin nhắn");
            return;
        }

        String groupName = parts[0].trim();
        String content   = parts[1].trim();

        if (!groups.containsKey(groupName)) {
            sendToSelf("ERROR: Nhóm [" + groupName +
                    "] không tồn tại!");
            return;
        }

        if (!groups.get(groupName).contains(username)) {
            sendToSelf("ERROR: Bạn chưa tham gia nhóm [" +
                    groupName + "]!");
            sendToSelf("Dùng lệnh JOIN_GROUP:" + groupName +
                    " để tham gia trước");
            return;
        }

        // Phát sóng tin nhắn đến tất cả thành viên nhóm
        String message = "[" + groupName + "] " +
                username + ": " + content;
        broadcastToGroup(groupName, message, null);

        // Lưu vào database
        db.saveGroupMessage(username, groupName, content);

        log.debug("Group message sent | sender={} | group={} | " +
                "contentLength={}", username, groupName,
                content.length());
    }

    private void handlePrivateMsg(String params) {
        if (!checkLoggedIn()) return;

        // Cắt tại dấu | đầu tiên (limit=2)
        String[] parts = params.split("\\|", 2);

        if (parts.length < 2 ||
                parts[0].trim().isEmpty() ||
                parts[1].trim().isEmpty()) {
            sendToSelf("ERROR: Cú pháp sai!");
            sendToSelf("Đúng: PRIVATE_MSG:TênNgười|Nội dung");
            return;
        }

        String recipient = parts[0].trim();
        String content   = parts[1].trim();

        // Không thể nhắn cho chính mình
        if (recipient.equals(username)) {
            sendToSelf("ERROR: Không thể gửi tin nhắn cho chính mình!");
            return;
        }

        // Kiểm tra người nhận có online không
        ClientHandler recipientHandler = onlineUsers.get(recipient);
        if (recipientHandler == null) {
            sendToSelf("ERROR: Người dùng [" + recipient +
                    "] không online!");
            sendToSelf("Người dùng online hiện tại: " +
                    onlineUsers.keySet());
            return;
        }

        // Gửi tin nhắn
        recipientHandler.sendToSelf(
                "[Riêng tư từ " + username + "]: " + content);
        sendToSelf(
                "[Riêng tư đến " + recipient + "]: " + content);

        // Lưu vào database
        db.savePrivateMessage(username, recipient, content);

        log.debug("Private message sent | from={} | to={}",
                username, recipient);
    }

    private void handleGetHistory(String groupName) {
        if (!checkLoggedIn()) return;

        groupName = groupName.trim();

        if (groupName.isEmpty()) {
            sendToSelf("ERROR: Vui lòng nhập tên nhóm!");
            sendToSelf("Đúng: GET_HISTORY:TênNhóm");
            return;
        }

        String history = db.getGroupHistory(groupName);
        sendToSelf(history);

        log.debug("History requested | username={} | group={}",
                username, groupName);
    }

    private void handleExit() {
        sendToSelf("OK: Tạm biệt [" + username + "]! " +
                "Hẹn gặp lại.");
        cleanup();
    }

    // ===================== PHƯƠNG THỨC TIỆN ÍCH =====================

    /**
     * Gửi tin nhắn đến chính client này
     */
    public void sendToSelf(String message) {
        if (out != null && !socket.isClosed()) {
            out.println(message);
        }
    }

    /**
     * Gửi tin nhắn đến tất cả thành viên của một nhóm
     *
     * @param groupName   Tên nhóm
     * @param message     Nội dung tin nhắn
     * @param excludeUser Người dùng bị loại trừ (null = gửi cho tất cả)
     */
    private void broadcastToGroup(
            String groupName, String message, String excludeUser) {

        Set<String> members = groups.get(groupName);
        if (members == null) return;

        int sentCount = 0;
        for (String memberName : members) {
            if (memberName.equals(excludeUser)) continue;

            ClientHandler handler = onlineUsers.get(memberName);
            if (handler != null) {
                handler.sendToSelf(message);
                sentCount++;
            }
        }

        log.debug("Broadcast | group={} | sentTo={} members",
                groupName, sentCount);
    }

    /**
     * Gửi thông báo hệ thống đến TẤT CẢ người dùng online
     */
    private void broadcastSystem(String message) {
        for (ClientHandler handler : onlineUsers.values()) {
            handler.sendToSelf("[System] " + message);
        }
    }

    /**
     * Kiểm tra người dùng đã đăng nhập chưa
     * Nếu chưa, gửi thông báo lỗi và trả về false
     */
    private boolean checkLoggedIn() {
        if (username == null) {
            sendToSelf("ERROR: Bạn cần đăng nhập trước!");
            sendToSelf("Gõ lệnh: LOGIN:TênCủaBạn");
            return false;
        }
        return true;
    }

    /**
     * Dọn dẹp tài nguyên khi client ngắt kết nối
     * Được gọi trong finally block — luôn chạy dù có lỗi hay không
     */
    private void cleanup() {
        if (username != null) {
            // Xóa khỏi danh sách online
            onlineUsers.remove(username);

            // Xóa khỏi tất cả các nhóm
            for (Set<String> members : groups.values()) {
                members.remove(username);
            }

            // Thông báo cho người khác
            broadcastSystem("[" + username + "] đã rời hệ thống.");

            // Tính thời gian session
            long sessionMs = System.currentTimeMillis() - connectTime;
            long sessionSec = sessionMs / 1000;

            log.info("Client cleanup | username={} | " +
                    "sessionDuration={}s | remainingOnline={}",
                    username, sessionSec, onlineUsers.size());

            username = null;
        }

        // Đóng socket
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            log.error("Error closing socket | error={}", e.getMessage());
        }
    }
}