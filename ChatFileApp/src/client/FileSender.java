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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import server.Packet;
import server.ServerCommands;

/**
 * Gửi file qua kênh dữ liệu (cổng 5001) tới FileRelay của server.
 * Bắt tay bằng gói tin JSON có 4 byte độ dài (cùng định dạng với kênh chat):
 *   gửi  {"type":"FILE_INIT","transferId":"...","role":"SENDER"}
 *   nhận {"type":"READY","offset":"..."}  (offset > 0 nghĩa là truyền tiếp)
 * Sau READY, gửi phần còn lại của file dưới dạng byte đã mã hóa AES/CTR theo khối 64KB rồi đóng kết nối.
 * AES/CTR là mật mã dòng nên "nhảy" được tới đúng điểm offset: cả hai bên cùng tính keystream
 * bắt đầu từ khối (offset / 16) với cùng khóa + IV, nên dữ liệu giải mã ra đúng dù gửi tiếp
 * (resume) thay vì gửi lại từ đầu.
 */
public class FileSender implements Runnable {
    private final String serverIp;
    private final int port;
    private final String transferId;
    private final File file;
    private final byte[] aesKey;
    private final byte[] aesIv;
    private final ProgressListener listener;

    public interface ProgressListener {
        void onProgress(int percent);
        void onComplete();
        void onError(String message);
    }

    public FileSender(String serverIp, int port, String transferId, File file,
                      byte[] aesKey, byte[] aesIv, ProgressListener listener) {
        this.serverIp = serverIp;
        this.port = port;
        this.transferId = transferId;
        this.file = file;
        this.aesKey = aesKey;
        this.aesIv = aesIv;
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
            // Điểm truyền tiếp phải là bội số 16 byte để khớp khối bộ đếm AES-CTR với bên nhận.
            long offset = CryptoUtil.alignDown(Math.min(reply.getLong("offset", 0), fileSize));
            long sent = offset;
            if (sent > 0) {
                long skipped = 0;
                while (skipped < sent) {
                    long n = fis.skip(sent - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
            }

            // 3. Mã hóa AES/CTR (bắt đầu đúng từ khối ứng với offset) rồi gửi theo từng khối
            Cipher cipher = CryptoUtil.createCipher(Cipher.ENCRYPT_MODE, aesKey, aesIv, offset);
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            int n;
            int lastPercent = (int) (sent * 100 / fileSize);
            while ((n = fis.read(buffer)) != -1) {
                byte[] encrypted = cipher.update(buffer, 0, n);
                if (encrypted != null && encrypted.length > 0) {
                    out.write(encrypted);
                }
                sent += n;
                int percent = (int) (sent * 100 / fileSize);
                if (percent != lastPercent) { // chỉ báo khi % thay đổi, tránh dồn việc cho giao diện
                    lastPercent = percent;
                    if (listener != null) listener.onProgress(percent);
                }
            }
            byte[] tail = cipher.doFinal(); // CTR không đệm nên thường rỗng, gọi cho đúng vòng đời Cipher
            if (tail != null && tail.length > 0) {
                out.write(tail);
            }
            out.flush();

            if (listener != null) listener.onComplete();
        } catch (IOException e) {
            fail("Lỗi truyền file: " + e.getMessage());
        } catch (GeneralSecurityException e) {
            fail("Lỗi mã hóa AES: " + e.getMessage());
        }
    }

    private void fail(String message) {
        if (listener != null) listener.onError(message);
    }
}
