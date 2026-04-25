package client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import protocol.MessageProtocol;
import server.ConfigLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;

/**
 * ChatClientSwing — Giao diện đồ họa cho Mini Chat v2.0
 *
 * Cải tiến so với v1.0:
 * - Tách NetworkManager thành inner class riêng biệt
 * - Logging với SLF4J
 * - Đọc cấu hình host/port từ ConfigLoader
 */
public class ChatClientSwing extends JFrame {

    private static final Logger log =
            LoggerFactory.getLogger(ChatClientSwing.class);

    // Đọc cấu hình từ ConfigLoader
    private static final String HOST =
            ConfigLoader.getString("client.host", "localhost");
    private static final int PORT =
            ConfigLoader.getInt("client.port", 6789);

    // ==================== THÀNH PHẦN GIAO DIỆN ====================
    private JTextArea chatArea;
    private JTextField inputField;
    private JTextField groupField;
    private JTextField recipientField;
    private JLabel statusLabel;

    // ==================== QUẢN LÝ MẠNG ====================
    private NetworkManager networkManager;
    private String username;

    // ==================== MÀU SẮC ====================
    private static final Color BG_DARK    = new Color(40,  44,  52);
    private static final Color BG_PANEL   = new Color(55,  60,  70);
    private static final Color TEXT_COLOR = Color.WHITE;
    private static final Color BTN_BLUE   = new Color(0,   120, 215);
    private static final Color BTN_GREEN  = new Color(34,  139, 34);
    private static final Color BTN_ORANGE = new Color(210, 105, 30);
    private static final Color BTN_RED    = new Color(178, 34,  34);

    public ChatClientSwing() {
        buildUI();
        connectToServer();
    }

    // ===================== XÂY DỰNG GIAO DIỆN =====================

    private void buildUI() {
        setTitle("Mini Chat v2.0 — Chưa đăng nhập");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 650);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(5, 5));
        getContentPane().setBackground(BG_DARK);

        add(buildTopPanel(),    BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        setupEventListeners();
        setupWindowCloseHandler();
    }

    /** Panel trên: hiển thị trạng thái kết nối */
    private JPanel buildTopPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBackground(BG_DARK);
        panel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        statusLabel = new JLabel("● Chưa kết nối");
        statusLabel.setForeground(Color.RED);
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        panel.add(statusLabel);

        JLabel versionLabel = new JLabel("   Mini Chat v2.0");
        versionLabel.setForeground(Color.GRAY);
        versionLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        panel.add(versionLabel);

        return panel;
    }

    /** Panel giữa: khung hiển thị tin nhắn */
    private JScrollPane buildCenterPanel() {
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setBackground(BG_DARK);
        chatArea.setForeground(TEXT_COLOR);
        chatArea.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setBorder(
                BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scroll = new JScrollPane(chatArea);
        scroll.setBorder(
                BorderFactory.createLineBorder(BG_PANEL, 2));
        return scroll;
    }

    /** Panel dưới: ô nhập và các nút */
    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBackground(BG_PANEL);
        panel.setBorder(
                BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel inputsPanel = new JPanel(
                new GridLayout(3, 1, 0, 5));
        inputsPanel.setBackground(BG_PANEL);

        // Hàng 1: Ô nhập tin nhắn
        inputsPanel.add(buildInputRow());

        // Hàng 2: Tên nhóm + Người nhận
        inputsPanel.add(buildParamRow());

        // Hàng 3: Các nút chức năng
        inputsPanel.add(buildButtonRow());

        panel.add(inputsPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildInputRow() {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setBackground(BG_PANEL);

        JLabel label = new JLabel(" Tin nhắn: ");
        label.setForeground(TEXT_COLOR);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        inputField = new JTextField();
        styleTextField(inputField);

        row.add(label,      BorderLayout.WEST);
        row.add(inputField, BorderLayout.CENTER);
        return row;
    }

    private JPanel buildParamRow() {
        JPanel row = new JPanel(new GridLayout(1, 4, 5, 0));
        row.setBackground(BG_PANEL);

        JLabel groupLabel = new JLabel(" Nhóm: ");
        groupLabel.setForeground(TEXT_COLOR);
        groupLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        groupField = new JTextField();
        styleTextField(groupField);

        JLabel recipientLabel = new JLabel(" Người nhận: ");
        recipientLabel.setForeground(TEXT_COLOR);
        recipientLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        recipientField = new JTextField();
        styleTextField(recipientField);

        row.add(groupLabel);
        row.add(groupField);
        row.add(recipientLabel);
        row.add(recipientField);
        return row;
    }

    private JPanel buildButtonRow() {
        JPanel row = new JPanel(new GridLayout(1, 5, 5, 0));
        row.setBackground(BG_PANEL);

        row.add(createButton("Tạo nhóm",    BTN_BLUE,   "btnCreate"));
        row.add(createButton("Vào nhóm",    BTN_GREEN,  "btnJoin"));
        row.add(createButton("Gửi nhóm",    BTN_BLUE,   "btnSendGroup"));
        row.add(createButton("Gửi riêng",   BTN_ORANGE, "btnSendPrivate"));
        row.add(createButton("Lịch sử chat",BTN_RED,    "btnHistory"));

        return row;
    }

    /** Gắn sự kiện cho các nút và phím Enter */
    private void setupEventListeners() {
        // Lấy các nút từ giao diện
        JButton btnCreate      = findButton("btnCreate");
        JButton btnJoin        = findButton("btnJoin");
        JButton btnSendGroup   = findButton("btnSendGroup");
        JButton btnSendPrivate = findButton("btnSendPrivate");
        JButton btnHistory     = findButton("btnHistory");

        if (btnCreate      != null)
            btnCreate.addActionListener(e -> onCreateGroup());
        if (btnJoin        != null)
            btnJoin.addActionListener(e -> onJoinGroup());
        if (btnSendGroup   != null)
            btnSendGroup.addActionListener(e -> onSendGroupMsg());
        if (btnSendPrivate != null)
            btnSendPrivate.addActionListener(e -> onSendPrivateMsg());
        if (btnHistory     != null)
            btnHistory.addActionListener(e -> onViewHistory());

        // Nhấn Enter trong ô tin nhắn = gửi nhóm
        inputField.addActionListener(e -> onSendGroupMsg());
    }

    /** Xử lý khi đóng cửa sổ */
    private void setupWindowCloseHandler() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (networkManager != null) {
                    networkManager.send(MessageProtocol.EXIT);
                }
                log.info("Client window closed | username={}", username);
            }
        });
    }

    // ===================== KẾT NỐI MẠNG =====================

    private void connectToServer() {
        log.info("Connecting to server | host={} | port={}", HOST, PORT);

        networkManager = new NetworkManager(HOST, PORT);

        if (networkManager.connect()) {
            // Cập nhật giao diện
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("● Đã kết nối đến Server");
                statusLabel.setForeground(Color.GREEN);
            });

            // Bắt đầu lắng nghe tin nhắn từ server
            networkManager.startListening(this::onMessageReceived);

            // Hỏi tên đăng nhập
            SwingUtilities.invokeLater(this::showLoginDialog);

        } else {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("● Mất kết nối");
                statusLabel.setForeground(Color.RED);
                showMessage("❌ Không thể kết nối đến Server!");
                showMessage("   Địa chỉ: " + HOST + ":" + PORT);
                showMessage("   Hãy khởi động ChatServer.java trước.");
            });
        }
    }

    /** Callback: được gọi mỗi khi nhận được tin từ server */
    private void onMessageReceived(String message) {
        SwingUtilities.invokeLater(() -> showMessage(message));
    }

    /** Hiển thị hộp thoại đăng nhập */
    private void showLoginDialog() {
        String name = JOptionPane.showInputDialog(
                this,
                "Nhập tên đăng nhập của bạn:",
                "Đăng nhập Mini Chat v2.0",
                JOptionPane.QUESTION_MESSAGE);

        if (name != null && !name.trim().isEmpty()) {
            username = name.trim();
            setTitle("Mini Chat v2.0 — " + username);
            networkManager.send(MessageProtocol.buildLogin(username));
            log.info("Sending login | username={}", username);
        } else {
            log.info("Login cancelled by user");
            System.exit(0);
        }
    }

    // ===================== XỬ LÝ NÚT =====================

    private void onCreateGroup() {
        String group = groupField.getText().trim();
        if (group.isEmpty()) {
            showWarning("Vui lòng nhập tên nhóm vào ô 'Nhóm'!");
            return;
        }
        networkManager.send(MessageProtocol.buildCreateGroup(group));
        log.debug("Create group | groupName={}", group);
    }

    private void onJoinGroup() {
        String group = groupField.getText().trim();
        if (group.isEmpty()) {
            showWarning("Vui lòng nhập tên nhóm vào ô 'Nhóm'!");
            return;
        }
        networkManager.send(MessageProtocol.buildJoinGroup(group));
        log.debug("Join group | groupName={}", group);
    }

    private void onSendGroupMsg() {
        String content = inputField.getText().trim();
        String group   = groupField.getText().trim();

        if (content.isEmpty()) {
            showWarning("Nội dung tin nhắn không được để trống!");
            return;
        }
        if (group.isEmpty()) {
            showWarning("Vui lòng nhập tên nhóm vào ô 'Nhóm'!");
            return;
        }

        networkManager.send(
                MessageProtocol.buildSendMsg(group, content));
        inputField.setText("");

        log.debug("Send group msg | group={} | length={}",
                group, content.length());
    }

    private void onSendPrivateMsg() {
        String content    = inputField.getText().trim();
        String recipient  = recipientField.getText().trim();

        if (content.isEmpty()) {
            showWarning("Nội dung tin nhắn không được để trống!");
            return;
        }
        if (recipient.isEmpty()) {
            showWarning("Vui lòng nhập tên người nhận!");
            return;
        }

        networkManager.send(
                MessageProtocol.buildPrivateMsg(recipient, content));
        inputField.setText("");

        log.debug("Send private msg | to={}", recipient);
    }

    private void onViewHistory() {
        String group = groupField.getText().trim();
        if (group.isEmpty()) {
            showWarning("Nhập tên nhóm vào ô 'Nhóm' để xem lịch sử!");
            return;
        }
        networkManager.send(
                MessageProtocol.buildGetHistory(group));
        log.debug("Request history | group={}", group);
    }

    // ===================== TIỆN ÍCH GIAO DIỆN =====================

    /** Hiển thị tin nhắn lên khung chat */
    private void showMessage(String msg) {
        chatArea.append(msg + "\n");
        // Tự động cuộn xuống tin mới nhất
        chatArea.setCaretPosition(
                chatArea.getDocument().getLength());
    }

    /** Hiển thị hộp thoại cảnh báo */
    private void showWarning(String message) {
        JOptionPane.showMessageDialog(
                this, message, "Thiếu thông tin",
                JOptionPane.WARNING_MESSAGE);
    }

    /** Style cho các ô nhập liệu */
    private void styleTextField(JTextField field) {
        field.setBackground(BG_DARK);
        field.setForeground(TEXT_COLOR);
        field.setCaretColor(Color.WHITE);
        field.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BTN_BLUE),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    /** Tạo nút với style thống nhất */
    private JButton createButton(
            String text, Color bgColor, String name) {
        JButton btn = new JButton(text);
        btn.setName(name);
        btn.setBackground(bgColor);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    /** Tìm nút theo name */
    private JButton findButton(String name) {
        return findButtonInContainer(getContentPane(), name);
    }

    private JButton findButtonInContainer(
            Container container, String name) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton btn) {
                if (name.equals(btn.getName())) return btn;
            } else if (comp instanceof Container c) {
                JButton found = findButtonInContainer(c, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ===================== INNER CLASS: NetworkManager =====================

    /**
     * NetworkManager — Quản lý kết nối mạng
     *
     * Tách riêng khỏi ChatClientSwing để đúng nguyên tắc
     * Single Responsibility: mỗi class chỉ làm một việc.
     *
     * ChatClientSwing: quản lý giao diện
     * NetworkManager: quản lý kết nối mạng
     */
    private static class NetworkManager {

        private static final Logger netLog =
                LoggerFactory.getLogger(NetworkManager.class);

        private final String host;
        private final int port;

        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private boolean connected = false;

        public NetworkManager(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** Thiết lập kết nối TCP đến server */
        public boolean connect() {
            try {
                socket = new Socket(host, port);

                out = new PrintWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(), "UTF-8"),
                        true);

                in = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(), "UTF-8"));

                connected = true;
                netLog.info("Connected to server | {}:{}", host, port);
                return true;

            } catch (IOException e) {
                netLog.error("Failed to connect | " +
                        "host={} | port={} | error={}",
                        host, port, e.getMessage());
                return false;
            }
        }

        /**
         * Bắt đầu luồng ngầm lắng nghe tin từ server
         *
         * @param callback Hàm được gọi mỗi khi nhận được tin nhắn
         */
        public void startListening(
                java.util.function.Consumer<String> callback) {

            Thread listenerThread = new Thread(() -> {
                netLog.info("Listener thread started");
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        callback.accept(line);
                    }
                } catch (IOException e) {
                    if (connected) {
                        netLog.error("Connection lost | error={}",
                                e.getMessage());
                        callback.accept(
                                "⚠️ Mất kết nối với Server!");
                    }
                } finally {
                    connected = false;
                }
            });

            // Daemon thread: tự tắt khi cửa sổ đóng
            listenerThread.setDaemon(true);
            listenerThread.setName("network-listener");
            listenerThread.start();
        }

        /** Gửi lệnh đến server */
        public void send(String message) {
            if (out != null && connected) {
                out.println(message);
                netLog.debug("Sent | message={}",
                        message.length() > 50
                                ? message.substring(0, 50) + "..."
                                : message);
            } else {
                netLog.warn("Cannot send — not connected | " +
                        "message={}", message);
            }
        }
    }

    // ===================== MAIN =====================

    public static void main(String[] args) {
        // Chạy giao diện trên Event Dispatch Thread
        SwingUtilities.invokeLater(() -> {
            ChatClientSwing client = new ChatClientSwing();
            client.setVisible(true);
        });
    }
}