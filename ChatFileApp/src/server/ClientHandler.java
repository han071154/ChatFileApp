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
import java.time.LocalDateTime;

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
    private volatile LocalDateTime loginTime;

    public ClientHandler(Socket socket, ChatServer server) {
        this.socket = socket;
        this.server = server;
    }

    public String getUsername() {
        return username;
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
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
            server.connectionClosed(this);
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
            loginTime = LocalDateTime.now();
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
            case ServerCommands.GROUP_FILE_UPLOAD:
                handleGroupFileUpload(packet);
                break;
            case ServerCommands.GROUP_FILE_DOWNLOAD:
                handleGroupFileDownload(packet);
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
                // Người mới vào thấy được các file nhóm đã chia sẻ trước đó
                for (GroupFileStore.GroupFile f : server.getFileRelay().getGroupFiles().listFiles(group)) {
                    send(GroupFileStore.fileAnnouncement(f));
                }
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

    // GROUP_FILE_UPLOAD {group, transferId, fileName, fileSize, sha256, aesKey, aesIv}
    //   -> GROUP_FILE_UPLOAD_OK {group, transferId}, rồi client tải lên qua cổng 5001 với role SENDER
    private void handleGroupFileUpload(Packet packet) {
        String group = packet.get("group");
        String transferId = packet.get("transferId");
        String fileName = packet.get("fileName") == null ? "" : packet.get("fileName").trim();
        String reason = null;
        if (transferId == null || transferId.isEmpty()) {
            reason = "Thiếu transferId";
        } else if (group == null || !server.isGroupMember(group, username)) {
            reason = "Bạn không ở trong nhóm '" + group + "'";
        } else if (fileName.isEmpty()) {
            reason = "Thiếu tên file";
        } else {
            reason = server.getFileRelay().getGroupFiles().authorizeUpload(transferId, username, getRemoteAddress(),
                    group, fileName, packet.getLong("fileSize", -1), packet.get(Protocol.FIELD_SHA256),
                    packet.get(Protocol.FIELD_AES_KEY), packet.get(Protocol.FIELD_AES_IV));
        }
        if (reason != null) {
            send(error(reason).put("transferId", transferId));
            return;
        }
        ServerLogger.info("'" + username + "' tải file '" + fileName + "' (" + packet.get("fileSize")
                + " bytes) lên nhóm '" + group + "' [" + transferId + "]");
        send(new Packet(ServerCommands.GROUP_FILE_UPLOAD_OK).put("group", group).put("transferId", transferId));
    }

    // GROUP_FILE_DOWNLOAD {group, fileId, transferId}
    //   -> GROUP_FILE_DOWNLOAD_OK {..., sha256, aesKey, aesIv}, rồi client tải về qua cổng 5001 với role RECEIVER
    private void handleGroupFileDownload(Packet packet) {
        String group = packet.get("group");
        String fileId = packet.get("fileId");
        String transferId = packet.get("transferId");
        if (transferId == null || transferId.isEmpty()) {
            send(error("Thiếu transferId"));
            return;
        }
        if (group == null || !server.isGroupMember(group, username)) {
            send(error("Bạn không ở trong nhóm '" + group + "'").put("transferId", transferId));
            return;
        }
        GroupFileStore.GroupFile f = server.getFileRelay().getGroupFiles()
                .authorizeDownload(transferId, username, getRemoteAddress(), group, fileId);
        if (f == null) {
            send(error("File không còn trên server hoặc phiên tải về không hợp lệ").put("transferId", transferId));
            return;
        }
        ServerLogger.info("'" + username + "' tải về file '" + f.fileName + "' của nhóm '" + group
                + "' [" + transferId + "]");
        send(new Packet(ServerCommands.GROUP_FILE_DOWNLOAD_OK)
                .put("group", group)
                .put("fileId", f.id)
                .put("transferId", transferId)
                .put("fileName", f.fileName)
                .put("fileSize", f.fileSize)
                .put(Protocol.FIELD_SHA256, f.sha256)
                .put(Protocol.FIELD_AES_KEY, f.aesKey)
                .put(Protocol.FIELD_AES_IV, f.aesIv));
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

        // FILE_CANCEL {transferId} không có "to": hủy phiên tải lên/tải về kho file nhóm
        if (ServerCommands.FILE_CANCEL.equals(type) && otherUser == null) {
            if (server.getFileRelay().getGroupFiles().cancel(transferId, username)) {
                ServerLogger.info("'" + username + "' hủy tải file nhóm [" + transferId + "]");
            }
            return;
        }

        if (Protocol.CMD_FILE_REQ.equals(type)) {
            ServerLogger.info("'" + username + "' yêu cầu gửi file '" + packet.get("fileName") + "' ("
                    + packet.get("fileSize") + " bytes) cho '" + otherUser + "' [" + transferId + "]");
            if (otherUser != null && server.getUser(otherUser) != null) {
                server.getFileRelay().noteRequest(transferId, username, otherUser,
                        packet.get("fileName"), packet.getLong("fileSize", -1));
            }
        } else if (Protocol.CMD_FILE_RESP.equals(type)) {
            boolean accepted = Protocol.STATUS_ACCEPT.equals(packet.get("status"));
            ClientHandler sender = otherUser == null ? null : server.getUser(otherUser);
            if (accepted && sender != null) {
                // otherUser là người gửi file, user hiện tại là người nhận
                server.getFileRelay().authorize(transferId, otherUser, sender.getRemoteAddress(),
                        username, getRemoteAddress());
            } else if (!accepted) {
                server.getFileRelay().markRejected(transferId, username);
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
            if (out == null || socket.isClosed()) {
                return; // client đang thoát, bỏ qua
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

    /** Đóng kết nối từ phía server (dừng server); luồng đọc sẽ thoát và tự dọn dẹp. */
    public void disconnect() {
        close();
    }

    /** Server chủ động ngắt một user: báo lý do cho client rồi đóng kết nối. */
    public void kick(String reason) {
        send(error(reason));
        ServerLogger.warn("Server ngắt kết nối " + describe() + ": " + reason);
        close();
    }

    private void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
