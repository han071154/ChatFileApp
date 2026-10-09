package server;

import common.Protocol;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

/**
 * Giao diện quản lý server: bật/tắt server, xem user online, nhóm chat, các phiên truyền file và log.
 * Dữ liệu được làm mới định kỳ (500 ms) từ ChatServer/FileRelay; log nhận trực tiếp từ ServerLogger.
 * Chạy: Run File (Shift+F6) trên file này. ChatServer.main vẫn dùng được để chạy server không giao diện.
 */
public class ServerFrame extends JFrame {
    private static final int REFRESH_MS = 500;
    private static final int MAX_LOG_LINES = 2000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ---------- Màu sắc, font: dùng chung bảng màu với ChatFrame của client ----------
    private static final Color PRIMARY = new Color(0x2563EB);
    private static final Color DANGER = new Color(0xDC2626);
    private static final Color HEADER_BG = new Color(0x1E293B);
    private static final Color PAGE_BG = new Color(0xF3F4F6);
    private static final Color LINE = new Color(0xD9DEE5);
    private static final Color TEXT_MAIN = new Color(0x111827);
    private static final Color TEXT_MUTED = new Color(0x6B7280);
    private static final Color OK = new Color(0x4ADE80);
    private static final Color OFF = new Color(0x9CA3AF);
    private static final Color WARN = new Color(0xFBBF24);
    private static final String FONT_NAME = "Segoe UI";

    private static Font ui(int style, int size) {
        return new Font(FONT_NAME, style, size);
    }

    // ---------- Trạng thái server ----------
    private ChatServer chatServer;
    private FileRelay fileRelay;
    private long startedAt;

    // ---------- Thành phần giao diện ----------
    private final JLabel statusLabel = new JLabel();
    private final JLabel uptimeLabel = new JLabel(" ");
    private final JButton startButton = new FlatButton("Bắt đầu", PRIMARY);
    private final JButton stopButton = new FlatButton("Dừng", DANGER);
    private final JButton kickButton = new FlatButton("Ngắt kết nối", DANGER);
    private final JLabel onlineCount = new JLabel("0");
    private final JLabel groupCount = new JLabel("0");
    private final JLabel transferCount = new JLabel("0");

    private final DefaultTableModel userModel = readOnlyModel("Tên đăng nhập", "Địa chỉ IP", "Đăng nhập lúc");
    private final DefaultTableModel groupModel = readOnlyModel("Nhóm", "Số TV", "Thành viên");
    private final DefaultTableModel transferModel = readOnlyModel(
            "Lúc", "Người gửi", "Người nhận", "Tên file", "Dung lượng", "Tiến độ", "Trạng thái");
    private final JTable userTable = createTable(userModel);
    private final JTable groupTable = createTable(groupModel);
    private final JTable transferTable = createTable(transferModel);
    private final JTextArea logArea = new JTextArea();

    // Dữ liệu lần làm mới trước: chỉ vẽ lại bảng khi có thay đổi để không mất dòng đang chọn
    private List<List<Object>> lastUsers = Collections.emptyList();
    private List<List<Object>> lastGroups = Collections.emptyList();
    private List<List<Object>> lastTransfers = Collections.emptyList();

    public ServerFrame() {
        super("Chat Server - Quản lý");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(1180, 720);
        setMinimumSize(new Dimension(900, 560));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PAGE_BG);
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        setContentPane(root);

        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        kickButton.addActionListener(e -> kickSelectedUser());
        userTable.getSelectionModel().addListSelectionListener(e -> updateButtons());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });

        ServerLogger.addListener(line -> SwingUtilities.invokeLater(() -> appendLog(line)));
        new Timer(REFRESH_MS, e -> refresh()).start();
        updateButtons();
    }

    // ================== Dựng giao diện ==================

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(HEADER_BG);
        header.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new GridLayout(2, 1, 0, 2));
        JLabel title = new JLabel("CHAT SERVER");
        title.setFont(ui(Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        JLabel info = new JLabel("Cổng chat " + Protocol.CHAT_PORT + "  |  Cổng file " + Protocol.FILE_PORT
                + "  |  IP máy chủ: " + localAddresses());
        info.setFont(ui(Font.PLAIN, 12));
        info.setForeground(new Color(0xCBD5E1));
        left.add(title);
        left.add(info);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        statusLabel.setFont(ui(Font.BOLD, 13));
        uptimeLabel.setFont(ui(Font.PLAIN, 12));
        uptimeLabel.setForeground(new Color(0xCBD5E1));
        right.add(uptimeLabel);
        right.add(statusLabel);
        right.add(startButton);
        right.add(stopButton);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel buildBody() {
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel stats = new JPanel(new GridLayout(1, 3, 10, 0));
        stats.setOpaque(false);
        stats.add(statCard("User đang online", onlineCount));
        stats.add(statCard("Nhóm chat", groupCount));
        stats.add(statCard("Phiên truyền file đang mở", transferCount));
        body.add(stats, BorderLayout.NORTH);

        // Cột trái: user online + nhóm
        JPanel userButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        userButtons.setOpaque(false);
        userButtons.add(kickButton);
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                card("User đang online", new JScrollPane(userTable), userButtons),
                card("Nhóm chat", new JScrollPane(groupTable), null));
        leftSplit.setResizeWeight(0.55);
        styleSplit(leftSplit);

        // Cột phải: phiên truyền file + log
        transferTable.getColumnModel().getColumn(5).setCellRenderer(new ProgressRenderer());
        transferTable.getColumnModel().getColumn(6).setCellRenderer(new StateRenderer());
        setColumnWidths(transferTable, 70, 90, 90, 200, 80, 120, 130);
        setColumnWidths(userTable, 110, 110, 90);
        setColumnWidths(groupTable, 90, 50, 180);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(0x0F172A));
        logArea.setForeground(new Color(0xE2E8F0));
        logArea.setCaretColor(Color.WHITE);
        logArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel logButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        logButtons.setOpaque(false);
        JButton clearLog = new FlatButton("Xóa log", new Color(0xE5E7EB));
        clearLog.setForeground(TEXT_MAIN);
        clearLog.addActionListener(e -> logArea.setText(""));
        logButtons.add(clearLog);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                card("Phiên truyền file", new JScrollPane(transferTable), null),
                card("Nhật ký server (server.log)", new JScrollPane(logArea), logButtons));
        rightSplit.setResizeWeight(0.45);
        styleSplit(rightSplit);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, rightSplit);
        main.setResizeWeight(0.33);
        styleSplit(main);
        body.add(main, BorderLayout.CENTER);
        return body;
    }

    private static JPanel statCard(String label, JLabel value) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(8, 14, 8, 14)));
        JLabel l = new JLabel(label);
        l.setFont(ui(Font.PLAIN, 12));
        l.setForeground(TEXT_MUTED);
        value.setFont(ui(Font.BOLD, 22));
        value.setForeground(TEXT_MAIN);
        p.add(l, BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    /** Khung trắng có tiêu đề, nội dung ở giữa và (tùy chọn) hàng nút ở dưới. */
    private static JPanel card(String title, JScrollPane content, JPanel footer) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        JLabel t = new JLabel(title);
        t.setFont(ui(Font.BOLD, 14));
        t.setForeground(TEXT_MAIN);
        content.setBorder(BorderFactory.createLineBorder(LINE));
        content.getViewport().setBackground(Color.WHITE);
        p.add(t, BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        if (footer != null) {
            p.add(footer, BorderLayout.SOUTH);
        }
        return p;
    }

    private static void styleSplit(JSplitPane split) {
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(8);
        split.setContinuousLayout(true);
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JTable createTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFont(ui(Font.PLAIN, 13));
        table.setRowHeight(26);
        table.setShowVerticalLines(false);
        table.setGridColor(new Color(0xEEF0F3));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(new Color(0xDBEAFE));
        table.setSelectionForeground(TEXT_MAIN);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setFont(ui(Font.BOLD, 12));
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    private static void setColumnWidths(JTable table, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    // ================== Bật / tắt server ==================

    private void startServer() {
        if (chatServer != null) {
            return;
        }
        FileRelay relay = new FileRelay(Protocol.FILE_PORT);
        ChatServer server = new ChatServer(Protocol.CHAT_PORT, relay);
        fileRelay = relay;
        chatServer = server;
        startedAt = System.currentTimeMillis();

        Thread relayThread = new Thread(relay::start, "file-relay");
        relayThread.setDaemon(true);
        relayThread.start();

        Thread serverThread = new Thread(() -> {
            server.start(); // chỉ trả về khi server dừng hoặc không mở được cổng
            SwingUtilities.invokeLater(() -> onServerExited(server));
        }, "chat-server");
        serverThread.setDaemon(true);
        serverThread.start();
        updateButtons();
    }

    private void stopServer() {
        ChatServer server = chatServer;
        FileRelay relay = fileRelay;
        if (server == null) {
            return;
        }
        chatServer = null;
        fileRelay = null;
        // Đóng socket có thể chậm nếu nhiều client, không làm trên luồng giao diện
        Thread stopper = new Thread(() -> {
            server.stop();
            relay.stop();
        }, "server-stop");
        stopper.setDaemon(true);
        stopper.start();
        updateButtons();
    }

    /** Vòng lặp ChatServer kết thúc mà không phải do bấm Dừng (vd: cổng 5000 đang bận). */
    private void onServerExited(ChatServer server) {
        if (server == chatServer) {
            FileRelay relay = fileRelay;
            chatServer = null;
            fileRelay = null;
            relay.stop();
            updateButtons();
            JOptionPane.showMessageDialog(this,
                    "Server không chạy được. Xem chi tiết trong nhật ký bên dưới\n"
                            + "(thường do cổng " + Protocol.CHAT_PORT + " đang bị chương trình khác dùng).",
                    "Lỗi khởi động", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void kickSelectedUser() {
        int row = userTable.getSelectedRow();
        ChatServer server = chatServer;
        if (row < 0 || server == null) {
            return;
        }
        String username = (String) userModel.getValueAt(row, 0);
        int answer = JOptionPane.showConfirmDialog(this, "Ngắt kết nối user '" + username + "'?",
                "Ngắt kết nối", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        ClientHandler handler = server.getUser(username);
        if (handler != null) {
            handler.kick("Bạn đã bị server ngắt kết nối");
        }
    }

    private void onClose() {
        if (chatServer != null) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Server đang chạy. Dừng server và thoát?", "Thoát", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
            chatServer.stop();
            fileRelay.stop();
        }
        dispose();
        System.exit(0);
    }

    private void updateButtons() {
        boolean running = chatServer != null;
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        kickButton.setEnabled(running && userTable.getSelectedRow() >= 0);
        refreshStatus();
    }

    // ================== Làm mới dữ liệu ==================

    private void refresh() {
        refreshStatus();
        ChatServer server = chatServer;
        FileRelay relay = fileRelay;

        List<List<Object>> users = new ArrayList<>();
        List<List<Object>> groups = new ArrayList<>();
        if (server != null) {
            for (ClientHandler h : server.getOnlineHandlers()) {
                InetAddress address = h.getRemoteAddress();
                users.add(Arrays.asList(h.getUsername(),
                        address == null ? "" : address.getHostAddress(),
                        h.getLoginTime() == null ? "" : h.getLoginTime().format(TIME)));
            }
            for (Map.Entry<String, List<String>> e : server.getGroupsSnapshot().entrySet()) {
                groups.add(Arrays.asList(e.getKey(), e.getValue().size(), String.join(", ", e.getValue())));
            }
        }

        // Lịch sử truyền file giữ lại sau khi dừng server để còn xem kết quả
        List<List<Object>> transfers = lastTransfers;
        int active = 0;
        if (relay != null) {
            transfers = new ArrayList<>();
            List<FileRelay.TransferStatus> list = relay.getTransferStatuses();
            Collections.reverse(list); // mới nhất lên đầu
            for (FileRelay.TransferStatus s : list) {
                if (!s.isFinished()) active++;
                transfers.add(Arrays.asList(s.getCreatedAt().format(TIME), s.getSender(), s.getReceiver(),
                        s.getFileName(), formatBytes(s.getFileSize()), progressText(s), s.getState()));
            }
        }

        if (!users.equals(lastUsers)) {
            lastUsers = users;
            String selected = selectedValue(userTable, 0);
            fill(userModel, users);
            reselect(userTable, 0, selected);
        }
        if (!groups.equals(lastGroups)) {
            lastGroups = groups;
            fill(groupModel, groups);
        }
        if (transfers != lastTransfers && !transfers.equals(lastTransfers)) {
            lastTransfers = transfers;
            fill(transferModel, transfers);
        }
        onlineCount.setText(String.valueOf(users.size()));
        groupCount.setText(String.valueOf(groups.size()));
        transferCount.setText(String.valueOf(active));
        updateButtonsQuiet();
    }

    private void updateButtonsQuiet() {
        kickButton.setEnabled(chatServer != null && userTable.getSelectedRow() >= 0);
    }

    private void refreshStatus() {
        ChatServer server = chatServer;
        if (server == null) {
            statusLabel.setText("● Đã dừng");
            statusLabel.setForeground(OFF);
            uptimeLabel.setText(" ");
        } else if (server.isListening()) {
            statusLabel.setText("● Đang chạy");
            statusLabel.setForeground(OK);
            long s = Duration.ofMillis(System.currentTimeMillis() - startedAt).getSeconds();
            uptimeLabel.setText(String.format("Đã chạy %02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
        } else {
            statusLabel.setText("● Đang khởi động...");
            statusLabel.setForeground(WARN);
        }
    }

    private static void fill(DefaultTableModel model, List<List<Object>> rows) {
        model.setRowCount(0);
        for (List<Object> row : rows) {
            model.addRow(row.toArray());
        }
    }

    private static String selectedValue(JTable table, int column) {
        int row = table.getSelectedRow();
        return row < 0 ? null : String.valueOf(table.getValueAt(row, column));
    }

    private static void reselect(JTable table, int column, String value) {
        if (value == null) return;
        for (int i = 0; i < table.getRowCount(); i++) {
            if (value.equals(table.getValueAt(i, column))) {
                table.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private void appendLog(String line) {
        logArea.append(line + "\n");
        int extra = logArea.getLineCount() - MAX_LOG_LINES;
        if (extra > 0) { // chỉ giữ MAX_LOG_LINES dòng cuối để không tốn bộ nhớ
            try {
                logArea.replaceRange("", 0, logArea.getLineEndOffset(extra - 1));
            } catch (javax.swing.text.BadLocationException ignored) {
            }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength()); // tự cuộn xuống dòng mới nhất
    }

    // ================== Tiện ích hiển thị ==================

    /** "45%" / "1,2 MB" (khi không biết dung lượng) — cột Tiến độ vẽ bằng ProgressRenderer. */
    private static String progressText(FileRelay.TransferStatus s) {
        int percent = s.getPercent();
        return percent >= 0 ? percent + "%" : formatBytes(s.getBytes());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024);
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    /** Các địa chỉ IPv4 của máy (để client trên máy khác biết nhập IP nào). */
    private static String localAddresses() {
        List<String> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) list.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) {
        }
        return list.isEmpty() ? "127.0.0.1" : String.join(", ", list);
    }

    /** Nút phẳng bo góc giống AccentButton bên client. */
    private static class FlatButton extends JButton {
        private final Color base;

        FlatButton(String text, Color base) {
            super(text);
            this.base = base;
            setFont(ui(Font.BOLD, 13));
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setMargin(new Insets(6, 16, 6, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color bg;
            if (!isEnabled()) bg = new Color(0x64748B);
            else if (getModel().isPressed()) bg = base.darker();
            else if (getModel().isRollover()) bg = base.brighter();
            else bg = base;
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Vẽ cột Tiến độ thành thanh phần trăm. */
    private static class ProgressRenderer implements TableCellRenderer {
        private final JProgressBar bar = new JProgressBar(0, 100);
        private final DefaultTableCellRenderer plain = new DefaultTableCellRenderer();

        ProgressRenderer() {
            bar.setStringPainted(true);
            bar.setFont(ui(Font.PLAIN, 11));
            bar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            String text = String.valueOf(value);
            if (!text.endsWith("%")) {
                return plain.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }
            bar.setValue(Integer.parseInt(text.substring(0, text.length() - 1)));
            bar.setString(text);
            return bar;
        }
    }

    /** Tô màu cột Trạng thái: xanh = hoàn tất, đỏ = lỗi/hủy, xanh dương = đang truyền. */
    private static class StateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String state = String.valueOf(value);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(ui(Font.BOLD, 12));
            if (state.equals("Hoàn tất")) setForeground(new Color(0x16A34A));
            else if (state.equals("Đang truyền")) setForeground(PRIMARY);
            else if (state.startsWith("Chờ")) setForeground(TEXT_MUTED);
            else setForeground(DANGER);
            return this;
        }
    }

    public static void main(String[] args) {
        ServerLogger.init("server.log");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            ServerFrame frame = new ServerFrame();
            frame.setVisible(true);
            frame.startServer(); // mở giao diện là chạy server luôn
        });
    }
}
