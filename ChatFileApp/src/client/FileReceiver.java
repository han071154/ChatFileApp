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
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import server.Packet;
import server.ServerCommands;

/**
 * Nhận file qua kênh dữ liệu (cổng 5001) từ FileRelay của server.
 * Bắt tay bằng gói tin JSON có 4 byte độ dài:
 *   gửi  {"type":"FILE_INIT","transferId":"...","role":"RECEIVER","offset":"0"}
 *   nhận {"type":"READY",...}
 * Sau READY, đọc đúng fileSize byte thô rồi lưu vào thư mục saveDir (tự đổi tên nếu trùng).
 */
public class FileReceiver implements Runnable {
    private final String serverIp;
    private final int port;
    private final String transferId;
    private final String rawFileName;
    private final long fileSize;
    private final File saveDir;
    private final ProgressListener listener;

    public interface ProgressListener {
        void onProgress(int percent);
        void onComplete();
        void onError(String message);
    }

    public FileReceiver(String serverIp, int port, String transferId, String rawFileName,
                        long fileSize, File saveDir, ProgressListener listener) {
        this.serverIp = serverIp;
        this.port = port;
        this.transferId = transferId;
        this.rawFileName = rawFileName;
        this.fileSize = fileSize;
        this.saveDir = saveDir;
        this.listener = listener;
    }

    @Override
    public void run() {
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            fail("Không tạo được thư mục lưu: " + saveDir);
            return;
        }
        File targetFile = resolveUniqueFileName(saveDir, rawFileName);
        boolean complete = false;

        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(targetFile))) {

            // 1. Bắt tay
            Packet.write(out, new Packet(Protocol.CMD_FILE_INIT)
                    .put("transferId", transferId).put("role", Protocol.ROLE_RECEIVER).put("offset", 0));

            // 2. Chờ READY (hoặc ERROR)
            Packet reply = Packet.read(in);
            if (ServerCommands.ERROR.equals(reply.getType())) {
                throw new IOException(reply.get("reason"));
            }
            if (!ServerCommands.READY.equals(reply.getType())) {
                throw new IOException("Phản hồi không đúng từ server: " + reply.getType());
            }

            // 3. Đọc dữ liệu nhị phân (dùng chính luồng `in` để không mất byte đã được đệm)
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long total = 0;
            int lastPercent = -1;
            while (total < fileSize) {
                int want = (int) Math.min(buffer.length, fileSize - total);
                int n = in.read(buffer, 0, want);
                if (n == -1) {
                    break; // mất kết nối hoặc bên gửi hủy
                }
                bos.write(buffer, 0, n);
                total += n;
                int percent = (int) (total * 100 / fileSize);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    if (listener != null) listener.onProgress(percent);
                }
            }
            bos.flush();

            // 4. Kiểm tra đủ dung lượng
            if (total == fileSize) {
                complete = true;
            } else {
                fail("Nhận file thất bại: bị ngắt kết nối giữa chừng (thiếu " + (fileSize - total) + " bytes).");
            }
        } catch (IOException e) {
            fail("Lỗi Socket nhận file: " + e.getMessage());
        } finally {
            if (!complete && targetFile.exists()) {
                targetFile.delete(); // xóa file dở dang để không để lại file hỏng
            }
        }
        if (complete && listener != null) {
            listener.onComplete();
        }
    }

    private void fail(String message) {
        if (listener != null) listener.onError(message);
    }

    // Xử lý trùng tên: photo.png -> photo (1).png -> photo (2).png
    private File resolveUniqueFileName(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) return file;

        String name = fileName;
        String extension = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            name = fileName.substring(0, dotIdx);
            extension = fileName.substring(dotIdx);
        }
        int count = 1;
        while (file.exists()) {
            file = new File(dir, name + " (" + count + ")" + extension);
            count++;
        }
        return file;
    }
}