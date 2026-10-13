/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

import common.CryptoUtil;
import common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import server.Packet;
import server.ServerCommands;

/**
 * Client chat: chỉ đảm nhiệm kết nối và giao tiếp với ChatServer.
 * Luồng đọc socket chạy riêng để không làm treo Swing EDT.
 */
public class ChatClient {
    public interface Listener {
        void onConnected(String username);
        void onLoginFailed(String reason);
        void onDisconnected(String reason);
        void onError(String message);
        void onPacket(Packet packet);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile String username;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile byte[] messageKey; // khóa AES-256 để mã hóa/giải mã tin nhắn chat

    public ChatClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /** Đặt "Khóa mã hóa" cho tin nhắn: các client phải cùng mật khẩu mới đọc được tin của nhau. Để trống = mật khẩu mặc định. */
    public void setMessageKey(String passphrase) {
        this.messageKey = CryptoUtil.deriveKey(passphrase);
    }

    private byte[] messageKey() {
        byte[] k = messageKey;
        if (k == null) {
            setMessageKey(null);
            k = messageKey;
        }
        return k;
    }

    private String encryptContent(String content) throws IOException {
        try {
            return CryptoUtil.encryptText(messageKey(), content);
        } catch (GeneralSecurityException e) {
            throw new IOException("Không mã hóa được tin nhắn: " + e.getMessage(), e);
        }
    }

    /** Giải mã nội dung chat nhận được; tin không mã hóa được trả nguyên văn, sai khóa thì báo rõ thay vì hiện chuỗi rối. */
    public String decryptContent(String content) {
        if (!CryptoUtil.isEncrypted(content)) {
            return content;
        }
        try {
            return CryptoUtil.decryptText(messageKey(), content);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return "[Không giải mã được - sai khóa mã hóa]";
        }
    }

    /** Kết nối + đăng nhập. Hàm này phải được gọi từ Thread/SwingWorker, không gọi trực tiếp trên EDT. */
    public void connect(String username) throws IOException {
        if (connected.get()) {
            throw new IOException("Client đã kết nối.");
        }

        try {
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(host, port), 5000); // quá 5 giây không kết nối được thì báo lỗi
            newSocket.setKeepAlive(true);
            this.socket = newSocket;
            this.in = new DataInputStream(new BufferedInputStream(newSocket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(newSocket.getOutputStream()));

            send(new Packet(Protocol.CMD_LOGIN).put("username", username));

            // LOGIN_SUCCESS/LOGIN_FAILED phải được xử lý ngay trước khi bắt đầu read loop.
            Packet response = Packet.read(in);
            if (Protocol.CMD_LOGIN_OK.equals(response.getType())) {
                this.username = response.get("username");
                connected.set(true);
                fire(() -> listener.onConnected(this.username));
                startReaderThread();
            } else if (Protocol.CMD_LOGIN_FAIL.equals(response.getType())) {
                String reason = valueOr(response.get("reason"), "Đăng nhập thất bại.");
                closeInternal();
                fire(() -> listener.onLoginFailed(reason));
            } else {
                closeInternal();
                fire(() -> listener.onLoginFailed("Server phản hồi không hợp lệ: " + response.getType()));
            }
        } catch (IOException e) {
            closeInternal(); // không để rò socket khi lỗi giữa chừng
            throw e;
        }
    }

    private void startReaderThread() {
        Thread reader = new Thread(() -> {
            String reason = "Kết nối đã đóng.";
            try {
                while (connected.get()) {
                    Packet packet = Packet.read(in);
                    fire(() -> listener.onPacket(packet));
                }
            } catch (EOFException e) {
                reason = "Server đã đóng kết nối.";
            } catch (IOException e) {
                if (connected.get()) {
                    reason = "Mất kết nối: " + e.getMessage();
                }
            } finally {
                boolean wasConnected = connected.getAndSet(false);
                closeStreamsOnly();
                if (wasConnected) {
                    final String finalReason = reason;
                    fire(() -> listener.onDisconnected(finalReason));
                }
            }
        }, "chat-reader-" + (username == null ? "client" : username));
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized void send(Packet packet) throws IOException {
        if (!connected.get() && !Protocol.CMD_LOGIN.equals(packet.getType())) {
            throw new IOException("Chưa kết nối tới server.");
        }
        if (out == null) {
            throw new IOException("Socket chưa sẵn sàng.");
        }
        Packet.write(out, packet);
    }

    public void sendMessage(String toUser, String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        send(new Packet(Protocol.CMD_MSG)
                .put("to", toUser)
                .put("content", encryptContent(content)));
    }

    // ---- Chat nhóm (server đã hỗ trợ): người tạo nhóm tự động là thành viên ----
    public void createGroup(String group) throws IOException {
        send(new Packet(ServerCommands.GROUP_CREATE).put("group", group));
    }

    public void joinGroup(String group) throws IOException {
        send(new Packet(ServerCommands.GROUP_JOIN).put("group", group));
    }

    public void leaveGroup(String group) throws IOException {
        send(new Packet(ServerCommands.GROUP_LEAVE).put("group", group));
    }

    /** Gửi tin vào nhóm; server phát lại cho mọi thành viên (kể cả mình) nên không tự thêm vào khung chat. */
    public void sendGroupMessage(String group, String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        send(new Packet(ServerCommands.GROUP_MSG).put("group", group).put("content", encryptContent(content)));
    }

    /**
     * Gửi yêu cầu truyền file, kèm mã băm SHA-256 (kiểm tra toàn vẹn) và khóa AES + IV (mã hóa
     * luồng dữ liệu). Gửi lại với cùng transferId/sha256/aesKey/aesIv là cách thực hiện truyền
     * tiếp (resume) sau khi mạng gián đoạn: FileReceiver phía nhận sẽ tự khớp vào phần đã nhận dở.
     */
    public void sendFileRequest(String toUser, String transferId, String fileName, long fileSize,
                                String sha256, String aesKeyBase64, String aesIvBase64) throws IOException {
        send(new Packet(Protocol.CMD_FILE_REQ)
                .put("to", toUser)
                .put("transferId", transferId)
                .put("fileName", fileName)
                .put("fileSize", fileSize)
                .put(Protocol.FIELD_SHA256, sha256)
                .put(Protocol.FIELD_AES_KEY, aesKeyBase64)
                .put(Protocol.FIELD_AES_IV, aesIvBase64));
    }

    public void respondFile(String sender, String transferId, boolean accept) throws IOException {
        send(new Packet(Protocol.CMD_FILE_RESP)
                .put("to", sender)
                .put("transferId", transferId)
                .put("status", accept ? Protocol.STATUS_ACCEPT : Protocol.STATUS_REJECT));
    }

    public void cancelFile(String otherUser, String transferId) throws IOException {
        send(new Packet(ServerCommands.FILE_CANCEL)
                .put("to", otherUser)
                .put("transferId", transferId));
    }

    // ---- Kho file nhóm: tải lên server một lần, thành viên nào cần thì tự tải về ----

    /** Xin tải file lên nhóm; gửi lại cùng transferId/sha256/khóa AES để tải lên tiếp phần còn thiếu. */
    public void uploadGroupFile(String group, String transferId, String fileName, long fileSize,
                                String sha256, String aesKeyBase64, String aesIvBase64) throws IOException {
        send(new Packet(ServerCommands.GROUP_FILE_UPLOAD)
                .put("group", group)
                .put("transferId", transferId)
                .put("fileName", fileName)
                .put("fileSize", fileSize)
                .put(Protocol.FIELD_SHA256, sha256)
                .put(Protocol.FIELD_AES_KEY, aesKeyBase64)
                .put(Protocol.FIELD_AES_IV, aesIvBase64));
    }

    /** Xin tải về một file của nhóm; server trả GROUP_FILE_DOWNLOAD_OK kèm khóa AES + SHA-256. */
    public void downloadGroupFile(String group, String fileId, String transferId) throws IOException {
        send(new Packet(ServerCommands.GROUP_FILE_DOWNLOAD)
                .put("group", group)
                .put("fileId", fileId)
                .put("transferId", transferId));
    }

    /** Hủy phiên tải lên/tải về kho file nhóm (FILE_CANCEL không có "to"). */
    public void cancelGroupTransfer(String transferId) throws IOException {
        send(new Packet(ServerCommands.FILE_CANCEL).put("transferId", transferId));
    }

    public void logout() {
        if (connected.get()) {
            try {
                send(new Packet(ServerCommands.LOGOUT));
            } catch (IOException ignored) {
            }
        }
        connected.set(false);
        closeInternal();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String getUsername() {
        return username;
    }

    public String getHost() {
        return host;
    }

    private void closeInternal() {
        connected.set(false);
        closeStreamsOnly();
    }

    private synchronized void closeStreamsOnly() {
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        in = null;
        out = null;
        socket = null;
    }

    private void fire(Runnable callback) {
        SwingUtilities.invokeLater(callback);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}