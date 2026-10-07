package server;

import common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;

/**
 * Phục vụ một client trên kênh chat (cổng 5000), chạy trong Thread Pool của ChatServer.
 * - Bước 1: chờ LOGIN hợp lệ.
 * - Bước 2: vòng lặp đọc gói tin, định tuyến chat 1-1, chat nhóm và các lệnh thương lượng file.
 * - Khi client thoát hoặc mất kết nối: xóa khỏi danh sách online và thông báo cho mọi người.
 */
public class ClientHandler implements Runnable {
    private static final int MAX_USERNAME_LENGTH = 20;
    private static final int MAX_GROUP_NAME_LENGTH = 30;
    private static final String NAME_PATTERN = "[\\p{L}\\p{N}_.-]+"; // chữ (có dấu), số, _ . -

    private final Socket socket;
    private final ChatServer server;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile String username;

    public ClientHandler(Socket socket, ChatServer server) {
        this.socket = socket;
        this.server = server;
    }

    public String getUsername() {
        return username;
    }

    public InetAddress getRemoteAddress() {
        return socket.getInetAddress();
    }

    @Override
    public void run() {
        String address = String.valueOf(socket.getRemoteSocketAddress());
        ServerLogger.info("Kết nối mới từ " + address);
        try {
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            if (!handleLogin()) {
                return;
            }
            while (true) {
                Packet packet = Packet.read(in);
                if (ServerCommands.LOGOUT.equals(packet.getType())) {
                    break;
                }
                handlePacket(packet);
            }
        } catch (EOFException | SocketException e) {
            // Client đóng kết nối hoặc mạng bị ngắt: xử lý như thoát bình thường
        } catch (IOException e) {
            ServerLogger.warn("Lỗi kết nối với " + describe() + ": " + e.getMessage());
        } finally {
            if (username != null) {
                server.unregisterUser(username, this);
                ServerLogger.info("User '" + username + "' đã thoát");
            }
            close();
            ServerLogger.info("Đóng kết nối " + address);
        }
    }

    // ================== Đăng nhập ==================

    /** Đọc LOGIN cho tới khi thành công. Trả về false nếu client ngắt kết nối/LOGOUT trước đó. */
    private boolean handleLogin() throws IOException {
        while (true) {
            Packet packet = Packet.read(in);
            String type = packet.getType();
            if (ServerCommands.LOGOUT.equals(type)) {
                return false;
            }
            if (!Protocol.CMD_LOGIN.equals(type)) {
                send(error("Bạn cần đăng nhập trước"));
                continue;
            }

            String name = packet.get("username") == null ? "" : packet.get("username").trim();
            String reason = validateName(name, MAX_USERNAME_LENGTH, "Tên đăng nhập");
            if (reason == null && !server.registerUser(name, this)) {
                reason = "Tên '" + name + "' đang được sử dụng";
            }
            if (reason != null) {
                send(new Packet(Protocol.CMD_LOGIN_FAIL).put("reason", reason));
                ServerLogger.info("Đăng nhập thất bại từ " + socket.getRemoteSocketAddress() + ": " + reason);
                continue;
            }

            username = name;
            ServerLogger.info("User '" + username + "' đăng nhập từ " + socket.getRemoteSocketAddress());
            send(new Packet(Protocol.CMD_LOGIN_OK).put("username", username));
            send(server.groupListPacket());
            server.broadcast(new Packet(ServerCommands.USER_JOINED).put("username", username), username);
            server.broadcastUserList();
            return true;
        }
    }

    private static String validateName(String name, int maxLength, String label) {
        if (name.isEmpty()) {
            return label + " không được để trống";
        }
        if (name.length() > maxLength) {
            return label + " tối đa " + maxLength + " ký tự";
        }
        if (!name.matches(NAME_PATTERN)) {
            return label + " chỉ gồm chữ, số và các ký tự _ . -";
        }
        return null;
    }

    // ================== Định tuyến gói tin ==================

    private void handlePacket(Packet packet) {
        String type = packet.getType();
        switch (type) {
            case Protocol.CMD_MSG:
                handlePrivateMessage(packet);
                break;
            case ServerCommands.GROUP_CREATE:
            case ServerCommands.GROUP_JOIN:
            case ServerCommands.GROUP_LEAVE:
                handleGroupCommand(packet);
                break;
            case ServerCommands.GROUP_MSG:
                handleGroupMessage(packet);
                break;
            case Protocol.CMD_FILE_REQ:
            case Protocol.CMD_FILE_RESP:
            case ServerCommands.FILE_CANCEL:
                handleFileCommand(packet);
                break;
            default:
                send(error("Lệnh không hợp lệ: " + type));
        }
    }

    // MSG {to, content}  ->  người nhận nhận MSG {from, content}
    private void handlePrivateMessage(Packet packet) {
        String content = packet.get("content");
        if (content == null || content.isEmpty()) {
            send(error("Tin nhắn rỗng"));
            return;
        }
        forwardToUser(packet);
    }

    private void handleGroupCommand(Packet packet) {
        String type = packet.getType();
        String group = packet.get("group") == null ? "" : packet.get("group").trim();
        String reason = validateName(group, MAX_GROUP_NAME_LENGTH, "Tên nhóm");
        if (reason != null) {
            send(error(reason));
            return;
        }

        if (ServerCommands.GROUP_CREATE.equals(type)) {
            if (server.createGroup(group, username)) {
                ServerLogger.info("'" + username + "' tạo nhóm '" + group + "'");
            } else {
                send(error("Nhóm '" + group + "' đã tồn tại"));
            }
        } else if (ServerCommands.GROUP_JOIN.equals(type)) {
            if (server.joinGroup(group, username)) {
                ServerLogger.info("'" + username + "' vào nhóm '" + group + "'");
            } else {
                send(error("Nhóm '" + group + "' không tồn tại"));
            }
        } else {
            if (server.leaveGroup(group, username)) {
                ServerLogger.info("'" + username + "' rời nhóm '" + group + "'");
                // Người rời nhóm không còn nhận GROUP_UPDATE nên báo riêng danh sách thành viên rỗng
                send(new Packet(ServerCommands.GROUP_UPDATE).put("group", group).put("members", ""));
            } else {
                send(error("Bạn không ở trong nhóm '" + group + "'"));
            }
        }
    }

    // GROUP_MSG {group, content}  ->  mọi thành viên nhận GROUP_MSG {group, from, content}
    private void handleGroupMessage(Packet packet) {
        String group = packet.get("group");
        String content = packet.get("content");
        if (group == null || !server.isGroupMember(group, username)) {
            send(error("Bạn không ở trong nhóm '" + group + "'"));
            return;
        }
        if (content == null || content.isEmpty()) {
            send(error("Tin nhắn rỗng"));
            return;
        }
        server.sendToGroup(group, new Packet(ServerCommands.GROUP_MSG)
                .put("group", group)
                .put("from", username)
                .put("content", content));
    }

    /**
     * FILE_REQ / FILE_RESP / FILE_CANCEL: server chỉ chuyển tiếp nguyên gói tin (giữ mọi trường như
     * fileName, fileSize, sha256, offset...) và đổi "to" thành "from".
     * Khi người nhận ACCEPT thì đăng ký transferId với FileRelay để cho phép kết nối cổng 5001.
     */
    private void handleFileCommand(Packet packet) {
        String transferId = packet.get("transferId");
        if (transferId == null || transferId.isEmpty()) {
            send(error("Thiếu transferId"));
            return;
        }
        String type = packet.getType();
        String otherUser = packet.get("to");

        if (Protocol.CMD_FILE_REQ.equals(type)) {
            ServerLogger.info("'" + username + "' yêu cầu gửi file '" + packet.get("fileName") + "' ("
                    + packet.get("fileSize") + " bytes) cho '" + otherUser + "' [" + transferId + "]");
        } else if (Protocol.CMD_FILE_RESP.equals(type)) {
            boolean accepted = Protocol.STATUS_ACCEPT.equals(packet.get("status"));
            ClientHandler sender = otherUser == null ? null : server.getUser(otherUser);
            if (accepted && sender != null) {
                // otherUser là người gửi file, user hiện tại là người nhận
                server.getFileRelay().authorize(transferId, otherUser, sender.getRemoteAddress(),
                        username, getRemoteAddress());
            }
            ServerLogger.info("'" + username + "' " + (accepted ? "đồng ý" : "từ chối")
                    + " nhận file từ '" + otherUser + "' [" + transferId + "]");
        } else {
            server.getFileRelay().cancel(transferId, username);
            ServerLogger.info("'" + username + "' hủy truyền file [" + transferId + "]");
        }
        forwardToUser(packet);
    }

    /** Chuyển gói tin tới user trong trường "to", thay "to" bằng "from" = người gửi. */
    private void forwardToUser(Packet packet) {
        String to = packet.get("to");
        ClientHandler target = to == null ? null : server.getUser(to);
        if (target == null) {
            send(error("User '" + to + "' không online"));
            return;
        }
        target.send(packet.copy().remove("to").put("from", username));
    }

    // ================== Gửi / đóng ==================

    /** Gửi gói tin tới client này. Có thể được gọi từ nhiều luồng nên phải synchronized. */
    public void send(Packet packet) {
        synchronized (this) {
            if (out == null) {
                return;
            }
            try {
                Packet.write(out, packet);
            } catch (IOException e) {
                // Ghi lỗi => kết nối đã hỏng; đóng socket để luồng đọc thoát và dọn dẹp
                ServerLogger.warn("Không gửi được tới " + describe() + ": " + e.getMessage());
                close();
            }
        }
    }

    private static Packet error(String reason) {
        return new Packet(ServerCommands.ERROR).put("reason", reason);
    }

    private String describe() {
        return username != null ? "'" + username + "'" : String.valueOf(socket.getRemoteSocketAddress());
    }

    private void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
