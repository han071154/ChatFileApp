/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

import common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class FileSender implements Runnable {
    private final String serverIp;
    private final int port;
    private final String transferId;
    private final File file;
    private final ProgressListener listener;

    // Interface callback tiến trình (dùng để update thanh JProgressBar cho UI của Hân)
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
        // 1. Kiểm tra tính hợp lệ của tệp tin
        if (!file.exists() || !file.isFile()) {
            if (listener != null) {
                listener.onError("Tệp tin không tồn tại hoặc đường dẫn không hợp lệ.");
            }
            return;
        }

        long fileSize = file.length();
        if (fileSize == 0) {
            if (listener != null) {
                listener.onError("Tệp tin rỗng (0 bytes). Không thể gửi.");
            }
            return;
        }

        try (Socket socket = new Socket(serverIp, port);
             OutputStream os = socket.getOutputStream();
             BufferedOutputStream bos = new BufferedOutputStream(os);
             BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            // 2. Gửi lệnh bắt tay tới FileRelay của Thảo (FILE_INIT|transferId|SENDER\n)
            String initCmd = Protocol.CMD_FILE_INIT + Protocol.SEP + transferId + Protocol.SEP + Protocol.ROLE_SENDER + "\n";
            os.write(initCmd.getBytes(StandardCharsets.UTF_8));
            os.flush();

            // 3. Đợi tín hiệu sẵn sàng từ FileRelay ("READY")
            String serverSignal = reader.readLine();
            if (!"READY".equals(serverSignal)) {
                throw new IOException("Server relay từ chối hoặc phản hồi không đúng: " + serverSignal);
            }

            // 4. Truyền dữ liệu file theo từng khối chunk (64KB)
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long totalBytesRead = 0;
            int bytesRead;

            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                if (listener != null) {
                    int percent = (int) ((totalBytesRead * 100) / fileSize);
                    listener.onProgress(percent);
                }
            }
            bos.flush();

            // 5. Báo hoàn tất
            if (listener != null) {
                listener.onComplete();
            }

        } catch (IOException e) {
            if (listener != null) {
                listener.onError("Lỗi truyền file: " + e.getMessage());
            }
        }
    }
}
