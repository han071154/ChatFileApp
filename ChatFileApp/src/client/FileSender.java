/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

import common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import server.Packet;
import server.ServerCommands;

/**
 * Gửi file qua kênh dữ liệu (cổng 5001) tới FileRelay của server.
 * Bắt tay bằng gói tin JSON có 4 byte độ dài (cùng định dạng với kênh chat):
 *   gửi  {"type":"FILE_INIT","transferId":"...","role":"SENDER"}
 *   nhận {"type":"READY","offset":"..."}  (offset > 0 nghĩa là truyền tiếp)
 * Sau READY, gửi phần còn lại của file dưới dạng byte thô theo khối 64KB rồi đóng kết nối.
 */
public class FileSender implements Runnable {
    private final String serverIp;
    private final int port;
    private final String transferId;
    private final File file;
    private final ProgressListener listener;

    public interface ProgressListener {
        void onProgress(int percent);
        void onComplete();
        void onError(String message);
    }

    public FileSender(String serverIp, int port, String transferId, File file, ProgressListener listener) {
        this.serverIp = serverIp;
        this.port = port;
        this.transferId = transferId;
        this.file = file;
        this.listener = listener;
    }

    @Override
    public void run() {
        if (!file.exists() || !file.isFile()) {
            fail("Tệp tin không tồn tại hoặc đường dẫn không hợp lệ.");
            return;
        }
        long fileSize = file.length();
        if (fileSize == 0) {
            fail("Tệp tin rỗng (0 bytes). Không thể gửi.");
            return;
        }

        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             FileInputStream fis = new FileInputStream(file)) {

            // 1. Bắt tay
            Packet.write(out, new Packet(Protocol.CMD_FILE_INIT)
                    .put("transferId", transferId).put("role", Protocol.ROLE_SENDER));

            // 2. Chờ READY (hoặc ERROR) từ relay. Relay chỉ gửi READY khi người nhận cũng đã kết nối.
            Packet reply = Packet.read(in);
            if (ServerCommands.ERROR.equals(reply.getType())) {
                throw new IOException(reply.get("reason"));
            }
            if (!ServerCommands.READY.equals(reply.getType())) {
                throw new IOException("Phản hồi không đúng từ server: " + reply.getType());
            }
            long offset = reply.getLong("offset", 0);
            long sent = Math.min(offset, fileSize);
            if (sent > 0) {
                long skipped = 0;
                while (skipped < sent) {
                    long n = fis.skip(sent - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
            }

            // 3. Gửi dữ liệu theo từng khối
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            int n;
            int lastPercent = -1;
            while ((n = fis.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                sent += n;
                int percent = (int) (sent * 100 / fileSize);
                if (percent != lastPercent) { // chỉ báo khi % thay đổi, tránh dồn việc cho giao diện
                    lastPercent = percent;
                    if (listener != null) listener.onProgress(percent);
                }
            }
            out.flush();

            if (listener != null) listener.onComplete();
        } catch (IOException e) {
            fail("Lỗi truyền file: " + e.getMessage());
        }
    }

    private void fail(String message) {
        if (listener != null) listener.onError(message);
    }
}