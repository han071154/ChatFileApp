/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

import common.FileInfo;
import common.Message;
import common.Protocol;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import server.Packet;
import server.ServerCommands;

/** Giao diện Swing chính: đăng nhập, user online, chat 1-1 và gửi/nhận file. */
public class ChatFrame extends JFrame implements ChatClient.Listener {
    private final DefaultListModel<String> onlineModel = new DefaultListModel<>();
    private final JList<String> onlineList = new JList<>(onlineModel);
    private final DefaultListModel<String> groupModel = new DefaultListModel<>();
    private final JList<String> groupList = new JList<>(groupModel);
    private final JTabbedPane sideTabs = new JTabbedPane();
    private final JTextPane chatArea = new JTextPane();
    private final JScrollPane chatScroll = new JScrollPane(chatArea); // khung chat phải có thanh cuộn
    private final JTextField messageField = new JTextField();
    private final JButton sendButton = new AccentButton("Gửi", true);
    private final JButton fileButton = new AccentButton("Gửi file", false);
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel progressLabel = new JLabel("Sẵn sàng");
    private final JButton cancelButton = new AccentButton("Hủy", false);
    private final JLabel chatTitle = new JLabel("Chưa chọn cuộc trò chuyện");
    private final JLabel currentUserLabel = new JLabel("Chưa đăng nhập");

    private JTextField hostField;
    private JTextField usernameField;
    private JButton loginButton;

    private ChatClient client;
    private String selectedUser;
    private static final String SYS = "sys";                       // "cuộc trò chuyện" chứa thông báo hệ thống khi chưa chọn ai
    private String currentKey;                                     // "u:<tên>" (chat 1-1) hoặc "g:<nhóm>" (chat nhóm)
    private final java.util.Map<String, List<Entry>> histories = new java.util.HashMap<>(); // lịch sử theo từng cuộc trò chuyện
    private final java.util.Set<String> unread = new java.util.HashSet<>();                  // cuộc trò chuyện có tin chưa đọc
    private final java.util.Map<String, java.util.Set<String>> groupMembers = new java.util.HashMap<>(); // chỉ có nhóm mình đã vào
    private String groupToOpen;                                    // nhóm vừa tạo/vào, tự mở khi server xác nhận
    private JPanel chatRoot;                                       // chỉ dựng giao diện chat MỘT lần (tránh gắn trùng listener)
    private String activeTransferId; // phiên truyền file đang diễn ra (chỉ cho phép 1 phiên một lúc)
    private String activePeer;

    // ---------- Giao diện: màu sắc, font, nút bo góc ----------
    private static final Color PRIMARY = new Color(0x2563EB);
    private static final Color HEADER_BG = new Color(0x1E293B);
    private static final Color PAGE_BG = new Color(0xF3F4F6);
    private static final Color LINE = new Color(0xD9DEE5);
    private static final Color CHAT_BG = new Color(0xF9FAFB);
    private static final Color BUBBLE_OTHER = new Color(0xE5E7EB);
    private static final Color TEXT_MAIN = new Color(0x111827);
    private static final Color TEXT_MUTED = new Color(0x6B7280);
    private static final String FONT_NAME = "Segoe UI";

    private static Font ui(int style, int size) {
        return new Font(FONT_NAME, style, size);
    }

    private static void styleField(JTextField f) {
        f.setFont(ui(Font.PLAIN, 14));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(6, 10, 6, 10)));
    }

    /** Nút phẳng bo góc: accent = true là nút chính (màu xanh), false là nút phụ (màu xám). */
    private static class AccentButton extends JButton {
        private final boolean accent;

        AccentButton(String text, boolean accent) {
            super(text);
            this.accent = accent;
            setFont(ui(Font.BOLD, 13));
            setForeground(accent ? Color.WHITE : TEXT_MAIN);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setMargin(new Insets(6, 14, 6, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = accent ? PRIMARY : new Color(0xE5E7EB);
            Color bg;
            if (!isEnabled()) bg = new Color(0xE5E7EB);
            else if (getModel().isPressed()) bg = base.darker();
            else if (getModel().isRollover()) bg = accent ? new Color(0x1D4ED8) : new Color(0xD1D5DB);
            else bg = base;
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Một dòng trong lịch sử chat: tin của mình, tin của người khác hoặc thông báo hệ thống. */
    private static final class Entry {
        static final int MINE = 0, OTHER = 1, SYSTEM = 2;
        final int kind;
        final String who;
        final String time;
        final String text;

        Entry(int kind, String who, String time, String text) {
            this.kind = kind;
            this.who = who;
            this.time = time;
            this.text = text;
        }
    }

    public ChatFrame() {
        super("Chat & Truyền file - Client");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setSize(1050, 680);
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (client != null) client.logout(); // báo server trước khi thoát
            }
        });
        showLoginPanel();
    }

    private void showLoginPanel() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(PAGE_BG);

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE),
                BorderFactory.createEmptyBorder(28, 36, 28, 36)));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("CHAT CLIENT", SwingConstants.CENTER);
        title.setFont(ui(Font.BOLD, 28));
        title.setForeground(HEADER_BG);
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.weightx = 1;
        c.insets = new Insets(0, 0, 2, 0);
        card.add(title, c);

        JLabel sub = new JLabel("Chat & Truyền file", SwingConstants.CENTER);
        sub.setFont(ui(Font.PLAIN, 13));
        sub.setForeground(TEXT_MUTED);
        c.gridy++;
        c.insets = new Insets(0, 0, 20, 0);
        card.add(sub, c);

        // Cột nhãn có weightx = 0 (vừa chữ), cột ô nhập weightx = 1 (giãn hết phần còn lại) -> các hàng luôn thẳng cột
        c.gridwidth = 1;
        c.gridy++; c.gridx = 0; c.weightx = 0;
        c.insets = new Insets(8, 0, 8, 16);
        JLabel hostLabel = new JLabel("Server IP:");
        hostLabel.setFont(ui(Font.PLAIN, 14));
        card.add(hostLabel, c);
        hostField = new JTextField("127.0.0.1");
        styleField(hostField);
        c.gridx = 1; c.weightx = 1;
        c.insets = new Insets(8, 0, 8, 0);
        card.add(hostField, c);

        c.gridy++; c.gridx = 0; c.weightx = 0;
        c.insets = new Insets(8, 0, 8, 16);
        JLabel userLabel = new JLabel("Tên đăng nhập:");
        userLabel.setFont(ui(Font.PLAIN, 14));
        card.add(userLabel, c);
        usernameField = new JTextField();
        styleField(usernameField);
        c.gridx = 1; c.weightx = 1;
        c.insets = new Insets(8, 0, 8, 0);
        card.add(usernameField, c);

        c.gridx = 0; c.gridy++; c.gridwidth = 2; c.weightx = 1;
        c.insets = new Insets(18, 0, 8, 0);
        loginButton = new AccentButton("Đăng nhập", true);
        loginButton.setFont(ui(Font.BOLD, 14));
        loginButton.setPreferredSize(new Dimension(100, 42));
        card.add(loginButton, c);

        JLabel note = new JLabel("Server chat: cổng " + Protocol.CHAT_PORT + "  |  File: cổng " + Protocol.FILE_PORT,
                SwingConstants.CENTER);
        note.setFont(ui(Font.PLAIN, 12));
        note.setForeground(TEXT_MUTED);
        c.gridy++;
        c.insets = new Insets(8, 0, 0, 0);
        card.add(note, c);

        card.setPreferredSize(new Dimension(460, card.getPreferredSize().height));
        root.add(card);

        loginButton.addActionListener(e -> loginAsync());
        usernameField.addActionListener(e -> loginAsync());

        setContentPane(root);
        revalidate();
        repaint();
        usernameField.requestFocusInWindow();
    }

    private void loginAsync() {
        String host = hostField.getText().trim();
        String username = usernameField.getText().trim();
        if (host.isEmpty() || username.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Server IP và tên đăng nhập.", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        loginButton.setEnabled(false);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        client = new ChatClient(host, Protocol.CHAT_PORT, this);

        Thread loginThread = new Thread(() -> {
            try {
                client.connect(username);
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    loginButton.setEnabled(true);
                    setCursor(java.awt.Cursor.getDefaultCursor());
                    JOptionPane.showMessageDialog(this, "Không kết nối được server:\n" + ex.getMessage(),
                            "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "chat-login");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    private void buildChatPanel() {
        if (chatRoot != null) { // đăng nhập lại: dùng lại giao diện cũ, không gắn thêm listener
            setContentPane(chatRoot);
            setTitle("Chat & Truyền file - " + client.getUsername());
            updateChatTitle();
            updateInputState();
            revalidate();
            repaint();
            return;
        }
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setBackground(HEADER_BG);
        top.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
        currentUserLabel.setFont(ui(Font.BOLD, 15));
        currentUserLabel.setForeground(Color.WHITE);
        top.add(currentUserLabel, BorderLayout.WEST);
        JLabel status = new JLabel("● Đang online", SwingConstants.RIGHT);
        status.setFont(ui(Font.PLAIN, 13));
        status.setForeground(new Color(0x4ADE80));
        top.add(status, BorderLayout.EAST);

        onlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onlineList.setFixedCellHeight(46);
        onlineList.setCellRenderer(new ConversationRenderer(false));
        onlineList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && onlineList.getSelectedValue() != null) {
                groupList.clearSelection();
                selectedUser = onlineList.getSelectedValue();
                openConversation("u:" + selectedUser);
            }
        });

        // Khung chat: JTextPane để tin của mình căn phải (xanh), tin người khác căn trái (xám), thông báo hệ thống căn giữa
        chatArea.setEditable(false);
        chatArea.setFont(ui(Font.PLAIN, 14));
        chatArea.setBackground(CHAT_BG);
        chatArea.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        chatScroll.setBorder(BorderFactory.createEmptyBorder());
        chatScroll.getVerticalScrollBar().setUnitIncrement(16);

        chatTitle.setFont(ui(Font.BOLD, 15));
        chatTitle.setForeground(TEXT_MAIN);
        JPanel chatHeader = new JPanel(new BorderLayout());
        chatHeader.setBackground(Color.WHITE);
        chatHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LINE),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        chatHeader.add(chatTitle, BorderLayout.CENTER);

        // Hàng nhập tin: [Gửi file] [ô nhập co giãn] [Gửi] - các nút cùng cao 40px
        styleField(messageField);
        messageField.setPreferredSize(new Dimension(100, 40));
        fileButton.setPreferredSize(new Dimension(100, 40));
        sendButton.setPreferredSize(new Dimension(84, 40));
        JPanel compose = new JPanel(new BorderLayout(8, 0));
        compose.setOpaque(false);
        compose.add(fileButton, BorderLayout.WEST);
        compose.add(messageField, BorderLayout.CENTER);
        compose.add(sendButton, BorderLayout.EAST);

        // Hàng tiến trình: thanh mỏng + nhãn trạng thái cố định bề rộng (không bị nhảy khi đổi chữ)
        progressBar.setStringPainted(false);
        progressBar.setBorderPainted(false);
        progressBar.setPreferredSize(new Dimension(100, 8));
        JPanel barWrap = new JPanel(new GridBagLayout());
        barWrap.setOpaque(false);
        GridBagConstraints bc = new GridBagConstraints();
        bc.fill = GridBagConstraints.HORIZONTAL;
        bc.weightx = 1;
        barWrap.add(progressBar, bc);

        progressLabel.setFont(ui(Font.PLAIN, 12));
        progressLabel.setForeground(TEXT_MUTED);
        progressLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        progressLabel.setPreferredSize(new Dimension(150, 22));
        cancelButton.setPreferredSize(new Dimension(64, 26));
        cancelButton.setVisible(false);
        JPanel progressEast = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        progressEast.setOpaque(false);
        progressEast.add(progressLabel);
        progressEast.add(cancelButton);

        JPanel progressPanel = new JPanel(new BorderLayout(10, 0));
        progressPanel.setOpaque(false);
        progressPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        progressPanel.add(barWrap, BorderLayout.CENTER);
        progressPanel.add(progressEast, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        bottom.add(compose, BorderLayout.NORTH);
        bottom.add(progressPanel, BorderLayout.SOUTH);

        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.add(chatHeader, BorderLayout.NORTH);
        chatPanel.add(chatScroll, BorderLayout.CENTER);
        chatPanel.add(bottom, BorderLayout.SOUTH);

        // Cột trái cố định 250px, phần chat chiếm toàn bộ phần còn lại
        JPanel sideWrap = new JPanel(new BorderLayout());
        sideWrap.setPreferredSize(new Dimension(250, 0));
        sideWrap.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, LINE));
        sideWrap.add(buildSidePane(), BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(sideWrap, BorderLayout.WEST);
        root.add(chatPanel, BorderLayout.CENTER);

        chatRoot = root;
        setContentPane(root);
        sendButton.addActionListener(e -> sendMessage());
        fileButton.addActionListener(e -> chooseAndSendFile());
        cancelButton.addActionListener(e -> cancelTransfer());
        messageField.addActionListener(e -> sendMessage());

        setTitle("Chat & Truyền file - " + client.getUsername());
        updateInputState();
        revalidate();
        repaint();
    }

    /** Cột trái: thẻ "Online" (chat 1-1) và thẻ "Nhóm" (chat nhóm). */
    private JTabbedPane buildSidePane() {
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setFixedCellHeight(46);
        groupList.setCellRenderer(new ConversationRenderer(true));
        groupList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && groupList.getSelectedValue() != null) {
                onlineList.clearSelection();
                openConversation("g:" + groupList.getSelectedValue());
            }
        });

        JButton createBtn = new AccentButton("Tạo", false);
        JButton joinBtn = new AccentButton("Vào", false);
        JButton leaveBtn = new AccentButton("Rời", false);
        createBtn.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Tên nhóm mới (chữ, số, _ . -):", "Tạo nhóm", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.trim().isEmpty()) {
                groupToOpen = name.trim();
                try {
                    client.createGroup(groupToOpen);
                } catch (IOException ex) {
                    showError(ex.getMessage());
                }
            }
        });
        joinBtn.addActionListener(e -> {
            String g = groupList.getSelectedValue();
            if (g == null) {
                JOptionPane.showMessageDialog(this, "Hãy chọn một nhóm trong danh sách.", "Chưa chọn nhóm", JOptionPane.WARNING_MESSAGE);
            } else if (isMember(g)) {
                JOptionPane.showMessageDialog(this, "Bạn đã ở trong nhóm '" + g + "'.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            } else {
                groupToOpen = g;
                try {
                    client.joinGroup(g);
                } catch (IOException ex) {
                    showError(ex.getMessage());
                }
            }
        });
        leaveBtn.addActionListener(e -> {
            String g = groupList.getSelectedValue();
            if (g == null || !isMember(g)) {
                JOptionPane.showMessageDialog(this, "Hãy chọn một nhóm mà bạn đang tham gia.", "Chưa chọn nhóm", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                client.leaveGroup(g);
            } catch (IOException ex) {
                showError(ex.getMessage());
            }
        });

        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 3, 6, 0));
        buttons.setBackground(Color.WHITE);
        buttons.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        buttons.add(createBtn);
        buttons.add(joinBtn);
        buttons.add(leaveBtn);

        JScrollPane groupScroll = new JScrollPane(groupList);
        groupScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel groupPanel = new JPanel(new BorderLayout());
        groupPanel.add(groupScroll, BorderLayout.CENTER);
        groupPanel.add(buttons, BorderLayout.SOUTH);

        JScrollPane onlineScroll = new JScrollPane(onlineList);
        onlineScroll.setBorder(BorderFactory.createEmptyBorder());

        sideTabs.setFont(ui(Font.BOLD, 13));
        sideTabs.addTab("Online", onlineScroll);
        sideTabs.addTab("Nhóm", groupPanel);
        return sideTabs;
    }

    /** Hiển thị tên + ● (và in đậm) khi có tin chưa đọc; nhóm đã tham gia có dấu ✓. */
    private class ConversationRenderer extends javax.swing.DefaultListCellRenderer {
        private final boolean group;

        ConversationRenderer(boolean group) {
            this.group = group;
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            String name = String.valueOf(value);
            boolean hasUnread = unread.contains((group ? "g:" : "u:") + name);
            String text = name;
            if (group && isMember(name)) text += "  ✓";
            if (hasUnread) text += "  ●";
            super.getListCellRendererComponent(list, text, index, selected, false);
            setFont(ui(hasUnread ? Font.BOLD : Font.PLAIN, 14));
            setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 12));
            setBackground(selected ? new Color(0xDBEAFE) : Color.WHITE);
            setForeground(TEXT_MAIN);
            return this;
        }
    }

    // ---------- Cuộc trò chuyện hiện tại ----------

    private boolean isGroupConversation() {
        return currentKey != null && currentKey.startsWith("g:");
    }

    private String currentName() {
        return currentKey == null ? null : currentKey.substring(2);
    }

    private boolean isMember(String group) {
        java.util.Set<String> members = groupMembers.get(group);
        return members != null && members.contains(client.getUsername());
    }

    private void openConversation(String key) {
        currentKey = key;
        unread.remove(key);
        onlineList.repaint();
        groupList.repaint();
        renderHistory(key);
        updateChatTitle();
        updateInputState();
        messageField.requestFocusInWindow();
    }

    /** Khóa ô nhập khi chưa chọn cuộc trò chuyện hoặc chưa vào nhóm; nút Gửi file chỉ dùng cho chat 1-1. */
    private void updateInputState() {
        boolean group = isGroupConversation();
        boolean canType = currentKey != null && (!group || isMember(currentName()));
        messageField.setEnabled(canType);
        sendButton.setEnabled(canType);
        fileButton.setEnabled(currentKey != null && !group && activeTransferId == null);
    }

    private void updateChatTitle() {
        String target;
        if (currentKey == null) {
            target = "Chưa chọn cuộc trò chuyện";
        } else if (isGroupConversation()) {
            String g = currentName();
            target = isMember(g) ? "Nhóm: " + g + " (" + groupMembers.get(g).size() + " thành viên)"
                                 : "Nhóm: " + g + " — bạn chưa vào nhóm (bấm \"Vào\")";
        } else {
            target = "Đang chat với: " + currentName();
        }
        chatTitle.setText(target);
    }

    private void sendMessage() {
        if (client == null || !client.isConnected()) return;
        if (currentKey == null) {
            JOptionPane.showMessageDialog(this, "Hãy chọn một người dùng hoặc một nhóm để chat.", "Chưa chọn người nhận", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String content = messageField.getText().trim();
        if (content.isEmpty()) return;
        try {
            if (isGroupConversation()) {
                client.sendGroupMessage(currentName(), content); // server phát lại cho cả nhóm, tin sẽ hiện khi nhận lại
            } else {
                client.sendMessage(currentName(), content);
                appendChat(new Message(client.getUsername(), currentName(), content));
            }
            messageField.setText("");
        } catch (IOException ex) {
            showError(ex.getMessage());
        }
    }

    private void chooseAndSendFile() {
        if (isGroupConversation()) {
            JOptionPane.showMessageDialog(this, "Gửi file chỉ hỗ trợ chat 1-1. Hãy chọn một người dùng.", "Không hỗ trợ", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (activeTransferId != null) {
            JOptionPane.showMessageDialog(this, "Đang có một phiên truyền file. Hãy đợi hoặc bấm Hủy.", "Đang bận", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (selectedUser == null || selectedUser.equals(client.getUsername())) {
            JOptionPane.showMessageDialog(this, "Hãy chọn người nhận file trước.", "Chưa chọn người nhận", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (!file.isFile() || file.length() <= 0) {
            JOptionPane.showMessageDialog(this, "File không hợp lệ hoặc file rỗng.", "Lỗi file", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String transferId = UUID.randomUUID().toString();
        try {
            rememberPendingFile(transferId, file);
            beginTransfer(transferId, selectedUser);
            client.sendFileRequest(selectedUser, transferId, file.getName(), file.length());
            appendSystem("Đã gửi yêu cầu gửi file '" + file.getName() + "' tới " + selectedUser + ". Đang chờ đồng ý...");
            progressBar.setValue(0);
            progressLabel.setText("Chờ nhận file...");
        } catch (IOException ex) {
            pendingFiles.remove(transferId);
            pendingInfos.remove(transferId);
            endTransfer();
            showError(ex.getMessage());
        }
    }

    private void startSendingFile(String receiver, String transferId, String fileName, long fileSize) {
        // FileSender cần File thật; yêu cầu file đã được chọn sẽ được lưu ở pendingFiles.
        File file = pendingFiles.remove(transferId);
        if (file == null) {
            appendSystem("Không tìm thấy file đang chờ gửi: " + fileName);
            return;
        }

        progressBar.setValue(0);
        progressLabel.setText("Đang gửi 0%");
        Thread t = new Thread(new FileSender(client.getHost(), Protocol.FILE_PORT, transferId, file,
                new FileSender.ProgressListener() {
                    @Override public void onProgress(int percent) {
                        SwingUtilities.invokeLater(() -> updateProgress(percent, "Đang gửi"));
                    }
                    @Override public void onComplete() {
                        SwingUtilities.invokeLater(() -> {
                            updateProgress(100, "Gửi xong");
                            endTransfer();
                            appendSystem("Đã gửi file '" + fileName + "' tới " + receiver + ".");
                        });
                    }
                    @Override public void onError(String message) {
                        SwingUtilities.invokeLater(() -> {
                            updateProgress(0, "Lỗi");
                            appendSystem(message);
                            endTransfer();
                        });
                    }
                }), "file-sender-" + transferId);
        t.setDaemon(true);
        t.start();
    }

    private final java.util.Map<String, File> pendingFiles = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onConnected(String username) {
        buildChatPanel();
        currentUserLabel.setText("Đăng nhập: " + username);
        setCursor(java.awt.Cursor.getDefaultCursor());
    }

    @Override
    public void onLoginFailed(String reason) {
        loginButton.setEnabled(true);
        setCursor(java.awt.Cursor.getDefaultCursor());
        JOptionPane.showMessageDialog(this, reason, "Đăng nhập thất bại", JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void onDisconnected(String reason) {
        onlineModel.clear();
        chatArea.setText("");
        selectedUser = null;
        groupModel.clear();
        groupMembers.clear();
        histories.clear();
        unread.clear();
        currentKey = null;
        groupToOpen = null;
        pendingFiles.clear();
        pendingInfos.clear();
        endTransfer();
        updateProgress(0, "Sẵn sàng");
        setCursor(java.awt.Cursor.getDefaultCursor());
        JOptionPane.showMessageDialog(this, reason, "Mất kết nối", JOptionPane.WARNING_MESSAGE);
        showLoginPanel();
    }

    @Override
    public void onError(String message) {
        showError(message);
    }

    @Override
    public void onPacket(Packet packet) {
        String type = packet.getType();
        switch (type) {
            case Protocol.CMD_USER_LIST -> updateOnlineUsers(packet.get("users"));
            case ServerCommands.USER_JOINED -> appendSystem(packet.get("username") + " đã online.");
            case ServerCommands.USER_LEFT -> appendSystem(packet.get("username") + " đã offline.");
            case Protocol.CMD_MSG -> appendChat(new Message(packet.get("from"), client.getUsername(), packet.get("content")));
            case ServerCommands.FILE_CANCEL -> handleFileCancelled(packet);
            case ServerCommands.GROUP_LIST -> updateGroupList(packet.get("groups"));
            case ServerCommands.GROUP_UPDATE -> handleGroupUpdate(packet);
            case ServerCommands.GROUP_MSG -> appendChat(new Message(packet.get("from"), "g:" + packet.get("group"), packet.get("content")));
            case Protocol.CMD_FILE_REQ -> handleIncomingFileRequest(packet);
            case Protocol.CMD_FILE_RESP -> handleFileResponse(packet);
            case ServerCommands.ERROR -> appendSystem("Lỗi server: " + packet.get("reason"));
            default -> {
                // Các gói group có thể được server gửi; giao diện này tập trung chat 1-1.
            }
        }
    }

    private void updateOnlineUsers(String csv) {
        onlineModel.clear();
        if (csv == null || csv.isBlank()) return;
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.equals(client.getUsername()))
                .forEach(onlineModel::addElement);

        if (selectedUser != null && !containsOnline(selectedUser)) {
            selectedUser = null;
        } else if (selectedUser != null && ("u:" + selectedUser).equals(currentKey)) {
            onlineList.setSelectedValue(selectedUser, true); // giữ dòng đang chọn sau khi danh sách được làm mới
        }
    }

    private boolean containsOnline(String username) {
        for (int i = 0; i < onlineModel.size(); i++) {
            if (username.equals(onlineModel.get(i))) return true;
        }
        return false;
    }

    private void handleIncomingFileRequest(Packet packet) {
        String from = packet.get("from");
        String transferId = packet.get("transferId");
        // Chỉ lấy phần tên file: chặn người gửi chèn "../" để ghi file ra ngoài thư mục đã chọn
        String fileName = packet.get("fileName") == null ? "" : new File(packet.get("fileName")).getName();
        if (fileName.isEmpty()) fileName = "file_nhan_duoc";
        long fileSize = packet.getLong("fileSize", 0);

        try {
            if (activeTransferId != null) { // đang bận phiên khác -> tự từ chối
                client.respondFile(from, transferId, false);
                appendSystem("Đã tự động từ chối file '" + fileName + "' của " + from + " vì bạn đang truyền file khác.");
                return;
            }

            int answer = JOptionPane.showConfirmDialog(this,
                    "Người dùng '" + from + "' muốn gửi file:\n\n" + fileName
                            + "\nDung lượng: " + formatBytes(fileSize) + "\n\nBạn có nhận không?",
                    "Yêu cầu nhận file", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                client.respondFile(from, transferId, false);
                appendSystem("Đã từ chối file '" + fileName + "' từ " + from + ".");
                return;
            }

            // Chọn thư mục lưu TRƯỚC khi báo ACCEPT, để bên gửi không phải chờ trong lúc mình chọn
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Chọn thư mục lưu file");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                client.respondFile(from, transferId, false);
                appendSystem("Đã hủy chọn thư mục, từ chối nhận file '" + fileName + "'.");
                return;
            }
            File saveDir = chooser.getSelectedFile();

            beginTransfer(transferId, from);
            client.respondFile(from, transferId, true);
            startReceivingFile(from, transferId, fileName, fileSize, saveDir);
        } catch (IOException ex) {
            endTransfer();
            showError("Không gửi được phản hồi file: " + ex.getMessage());
        }
    }

    private void startReceivingFile(String sender, String transferId, String fileName, long fileSize, File saveDir) {
        progressBar.setValue(0);
        progressLabel.setText("Đang nhận 0%");
        appendSystem("Đang nhận file '" + fileName + "' từ " + sender + "...");

        Thread t = new Thread(new FileReceiver(client.getHost(), Protocol.FILE_PORT, transferId, fileName, fileSize,
                saveDir, new FileReceiver.ProgressListener() {
                    @Override public void onProgress(int percent) {
                        SwingUtilities.invokeLater(() -> updateProgress(percent, "Đang nhận"));
                    }
                    @Override public void onComplete() {
                        SwingUtilities.invokeLater(() -> {
                            updateProgress(100, "Nhận xong");
                            endTransfer();
                            appendSystem("Đã nhận file '" + fileName + "' vào " + saveDir.getAbsolutePath());
                        });
                    }
                    @Override public void onError(String message) {
                        SwingUtilities.invokeLater(() -> {
                            updateProgress(0, "Lỗi");
                            appendSystem(message);
                            endTransfer();
                        });
                    }
                }), "file-receiver-" + transferId);
        t.setDaemon(true);
        t.start();
    }

    private void handleFileResponse(Packet packet) {
        String from = packet.get("from");
        String transferId = packet.get("transferId");
        String status = packet.get("status");
        String receiver = from;

        if (Protocol.STATUS_ACCEPT.equals(status)) {
            appendSystem(receiver + " đã đồng ý nhận file.");
            FileInfo info = pendingInfos.remove(transferId);
            if (info != null) {
                startSendingFile(receiver, transferId, info.getFileName(), info.getFileSize());
            }
        } else {
            pendingFiles.remove(transferId);
            pendingInfos.remove(transferId);
            appendSystem(receiver + " đã từ chối nhận file.");
            updateProgress(0, "Bị từ chối");
            if (transferId.equals(activeTransferId)) endTransfer();
        }
    }

    // Thông tin (tên, dung lượng) của file đang chờ người nhận đồng ý
    private final java.util.Map<String, FileInfo> pendingInfos = new java.util.concurrent.ConcurrentHashMap<>();

    /** Đối phương hủy phiên truyền (server chuyển tiếp FILE_CANCEL). */
    private void handleFileCancelled(Packet packet) {
        String id = packet.get("transferId");
        appendSystem(packet.get("from") + " đã hủy phiên truyền file.");
        if (id != null && id.equals(activeTransferId)) {
            pendingFiles.remove(id);
            pendingInfos.remove(id);
            updateProgress(0, "Đã bị hủy");
            endTransfer();
        }
    }

    private void beginTransfer(String transferId, String peer) {
        activeTransferId = transferId;
        activePeer = peer;
        cancelButton.setVisible(true);
        updateInputState();
    }

    private void endTransfer() {
        activeTransferId = null;
        activePeer = null;
        cancelButton.setVisible(false);
        updateInputState();
    }

    /** Người dùng bấm Hủy: báo server đóng kênh 5001 và báo cho đối phương. */
    private void cancelTransfer() {
        if (activeTransferId == null) return;
        try {
            client.cancelFile(activePeer, activeTransferId);
        } catch (IOException ignored) {
        }
        pendingFiles.remove(activeTransferId);
        pendingInfos.remove(activeTransferId);
        appendSystem("Bạn đã hủy phiên truyền file.");
        updateProgress(0, "Đã hủy");
        endTransfer();
    }

    private void updateGroupList(String csv) {
        groupModel.clear();
        if (csv != null && !csv.isBlank()) {
            for (String g : csv.split(",")) {
                if (!g.trim().isEmpty()) groupModel.addElement(g.trim());
            }
        }
        groupMembers.keySet().removeIf(g -> !groupModel.contains(g)); // nhóm đã bị server xóa
        if (isGroupConversation() && groupModel.contains(currentName())) {
            groupList.setSelectedValue(currentName(), true); // giữ dòng đang chọn sau khi làm mới danh sách
        }
        updateChatTitle();
        updateInputState();
    }

    private void handleGroupUpdate(Packet packet) {
        String group = packet.get("group");
        String key = "g:" + group;
        java.util.Set<String> members = new java.util.HashSet<>();
        String csv = packet.get("members");
        if (csv != null && !csv.isBlank()) {
            for (String m : csv.split(",")) {
                if (!m.trim().isEmpty()) members.add(m.trim());
            }
        }
        String me = client.getUsername();
        boolean wasMember = isMember(group);
        java.util.Set<String> old = groupMembers.getOrDefault(group, java.util.Collections.emptySet());
        groupMembers.put(group, members);
        boolean nowMember = members.contains(me);

        if (nowMember && !groupModel.contains(group)) groupModel.addElement(group);
        if (nowMember && !wasMember) {
            appendTo(key, "[Hệ thống] Bạn đã vào nhóm '" + group + "'.");
        } else if (!nowMember && wasMember) {
            appendTo(key, "[Hệ thống] Bạn đã rời nhóm '" + group + "'.");
        } else if (nowMember) {
            for (String m : members) if (!old.contains(m)) appendTo(key, "[Hệ thống] " + m + " đã vào nhóm.");
            for (String m : old) if (!members.contains(m)) appendTo(key, "[Hệ thống] " + m + " đã rời nhóm.");
        }
        if (nowMember && group.equals(groupToOpen)) { // nhóm vừa tạo/vào: tự mở
            groupToOpen = null;
            sideTabs.setSelectedIndex(1);
            groupList.setSelectedValue(group, true);
        }
        groupList.repaint();
        if (key.equals(currentKey)) {
            updateChatTitle();
            updateInputState();
        }
    }

    private void updateProgress(int percent, String text) {
        progressBar.setValue(Math.max(0, Math.min(100, percent)));
        progressLabel.setText(text + (percent >= 0 ? " " + percent + "%" : ""));
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private void appendChat(Message m) {
        String from = m.getFrom() == null ? "Không rõ" : m.getFrom();
        boolean mine = from.equals(client.getUsername());
        String to = m.getTo();
        // Tin nhóm: to = "g:<nhóm>". Tin 1-1: cuộc trò chuyện là với người còn lại.
        String key = (to != null && to.startsWith("g:")) ? to : "u:" + (mine ? to : from);
        String content = m.getContent() == null ? "" : m.getContent();
        appendEntry(key, new Entry(mine ? Entry.MINE : Entry.OTHER, mine ? "Bạn" : from,
                m.getTimestamp().format(TIME_FORMAT), content));
    }

    private void appendSystem(String text) {
        appendTo(currentKey == null ? SYS : currentKey, "[Hệ thống] " + (text == null ? "" : text));
    }

    /** Thông báo hệ thống (dòng bắt đầu bằng "[Hệ thống] ") được hiển thị ở giữa, chữ xám nghiêng. */
    private void appendTo(String key, String line) {
        String prefix = "[Hệ thống] ";
        appendEntry(key, new Entry(Entry.SYSTEM, "", "", line.startsWith(prefix) ? line.substring(prefix.length()) : line));
    }

    /** Thêm một mục vào lịch sử; hiện ngay nếu đang mở cuộc trò chuyện đó, nếu không thì đánh dấu chưa đọc (●). */
    private void appendEntry(String key, Entry entry) {
        histories.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
        String shown = currentKey == null ? SYS : currentKey;
        if (key.equals(shown)) {
            insertEntry(entry);
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        } else {
            unread.add(key);
            onlineList.repaint();
            groupList.repaint();
        }
    }

    private void renderHistory(String key) {
        chatArea.setText("");
        List<Entry> h = histories.get(key);
        if (h != null) {
            for (Entry e : h) insertEntry(e);
        }
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    private static SimpleAttributeSet textStyle(int size, boolean bold, boolean italic, Color fg, Color bg) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, FONT_NAME);
        StyleConstants.setFontSize(a, size);
        StyleConstants.setBold(a, bold);
        StyleConstants.setItalic(a, italic);
        StyleConstants.setForeground(a, fg);
        if (bg != null) StyleConstants.setBackground(a, bg);
        return a;
    }

    private static SimpleAttributeSet paragraphStyle(int align, float left, float right, float below) {
        SimpleAttributeSet p = new SimpleAttributeSet();
        StyleConstants.setAlignment(p, align);
        StyleConstants.setLeftIndent(p, left);
        StyleConstants.setRightIndent(p, right);
        StyleConstants.setSpaceAbove(p, 0);
        StyleConstants.setSpaceBelow(p, below);
        return p;
    }

    /** Vẽ một mục vào khung chat: tin của mình căn phải nền xanh, tin người khác căn trái nền xám. */
    private void insertEntry(Entry e) {
        StyledDocument doc = chatArea.getStyledDocument();
        try {
            if (e.kind == Entry.SYSTEM) {
                String line = e.text + "\n";
                int s = doc.getLength();
                doc.insertString(s, line, textStyle(12, false, true, TEXT_MUTED, null));
                doc.setParagraphAttributes(s, line.length(),
                        paragraphStyle(StyleConstants.ALIGN_CENTER, 24, 24, 10), false);
                return;
            }
            boolean mine = e.kind == Entry.MINE;
            int align = mine ? StyleConstants.ALIGN_RIGHT : StyleConstants.ALIGN_LEFT;
            float left = mine ? 110 : 4;
            float right = mine ? 4 : 110;

            String header = (mine ? e.time : e.who + "   " + e.time) + "\n";
            int s1 = doc.getLength();
            doc.insertString(s1, header, textStyle(11, !mine, false, TEXT_MUTED, null));
            doc.setParagraphAttributes(s1, header.length(), paragraphStyle(align, left, right, 2), false);

            String body = "  " + e.text + "  ";
            int s2 = doc.getLength();
            doc.insertString(s2, body, textStyle(14, false, false, mine ? Color.WHITE : TEXT_MAIN,
                    mine ? PRIMARY : BUBBLE_OTHER));
            doc.insertString(s2 + body.length(), "\n", textStyle(14, false, false, TEXT_MAIN, null));
            doc.setParagraphAttributes(s2, body.length() + 1, paragraphStyle(align, left, right, 12), false);
        } catch (BadLocationException ignored) {
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message == null ? "Lỗi không xác định." : message,
                "Lỗi", JOptionPane.ERROR_MESSAGE);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    // Ghi nhớ file đã chọn cho tới khi người nhận ACCEPT.
    private void rememberPendingFile(String transferId, File file) {
        pendingFiles.put(transferId, file);
        pendingInfos.put(transferId, new FileInfo(transferId, client.getUsername(), selectedUser,
                file.getName(), file.length()));
    }
}