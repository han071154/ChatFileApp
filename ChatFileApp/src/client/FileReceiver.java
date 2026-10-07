/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

import common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class FileReceiver implements Runnable {
    private final String serverIp;
    private final int port;
    private final String transferId;
    private final String rawFileName;
    private final long fileSize;
    private final File saveDir;
    private final ProgressListener listener;

    // Interface callback tiến trình (dùng chung cho cả Sender và Receiver)
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
        // 1. Kiểm tra và tạo thư mục lưu nếu chưa tồn tại
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }

        // 2. Chống ghi đè file cũ
        File targetFile = resolveUniqueFileName(saveDir, rawFileName);

        try (Socket socket = new Socket(serverIp, port);
             OutputStream os = socket.getOutputStream();
             BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(targetFile))) {

            // 3. Gửi lệnh bắt tay (FILE_INIT|transferId|RECEIVER\n)
            String initCmd = Protocol.CMD_FILE_INIT + Protocol.SEP + transferId + Protocol.SEP + Protocol.ROLE_RECEIVER + "\n";
            os.write(initCmd.getBytes(StandardCharsets.UTF_8));
            os.flush();

            // 4. Đọc dữ liệu nhị phân theo từng chunk
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long totalBytesRead = 0;
            int bytesRead;

            while (totalBytesRead < fileSize) {
                // Chỉ đọc số byte còn lại cần đọc để tránh đọc tràn sang dữ liệu khác
                int bytesToRead = (int) Math.min(buffer.length, fileSize - totalBytesRead);
                bytesRead = bis.read(buffer, 0, bytesToRead);

                if (bytesRead == -1) {
                    break; // Mất kết nối mạng hoặc server đóng sớm
                }

                bos.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                if (listener != null && fileSize > 0) {
                    int percent = (int) ((totalBytesRead * 100) / fileSize);
                    listener.onProgress(percent);
                }
            }
            bos.flush();

            // 5. Kiểm tra tính toàn vẹn dung lượng
            if (totalBytesRead == fileSize) {
                if (listener != null) listener.onComplete();
            } else {
                // Nếu bị đứt gánh, xóa file tải dở để tránh file rác/hỏng
                if (targetFile.exists()) {
                    targetFile.delete();
                }
                if (listener != null) {
                    listener.onError("Nhận file thất bại: Bị ngắt kết nối giữa chừng (thiếu " + (fileSize - totalBytesRead) + " bytes).");
                }
            }

        } catch (IOException e) {
            if (listener != null) {
                listener.onError("Lỗi Socket nhận file: " + e.getMessage());
            }
        }
    }

    // Xử lý trùng tên: photo.png -> photo (1).png -> photo (2).png
    private File resolveUniqueFileName(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) return file;

        String name = fileName;
        String extension = "";
        int dotIdx = fileName.lastIndexOf(".");
        if (dotIdx != -1) {
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