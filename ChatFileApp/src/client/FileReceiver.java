/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package client;

import common.CryptoUtil;
import common.HashUtil;
import common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import server.Packet;
import server.ServerCommands;

/**
 * Nhận file qua kênh dữ liệu (cổng 5001) từ FileRelay của server.
 * Bắt tay bằng gói tin JSON có 4 byte độ dài:
 *   gửi  {"type":"FILE_INIT","transferId":"...","role":"RECEIVER","offset":"<số byte đã có>"}
 *   nhận {"type":"READY",...}
 * Sau READY, đọc đúng fileSize byte, giải mã AES/CTR rồi lưu vào thư mục saveDir.
 *
 * Truyền tiếp (resume): dữ liệu đang nhận dở được ghi vào một tệp tạm cố định theo transferId
 * (<transferId>.part trong saveDir). Nếu phiên bị ngắt giữa chừng, tệp tạm KHÔNG bị xóa; lần
 * gọi sau với cùng transferId sẽ mở lại đúng tệp đó, lấy độ dài hiện có làm offset tiếp tục
 * (làm tròn xuống bội số 16 byte để khớp khối bộ đếm AES-CTR), rồi chỉ xin server phần còn thiếu.
 * Sau khi nhận đủ, mã SHA-256 của toàn bộ file được so với giá trị bên gửi cung cấp để phát
 * hiện file bị lỗi/hỏng do mạng; nếu sai, tệp bị xóa và phải gửi lại từ đầu.
 */
public class FileReceiver implements Runnable {
    private final String serverIp;
    private final int port;
    private final String transferId;
    private final String rawFileName;
    private final long fileSize;
    private final File saveDir;
    private final byte[] aesKey;
    private final byte[] aesIv;
    private final String expectedSha256; // có thể null/rỗng nếu bên gửi không kèm (bỏ qua kiểm tra)
    private final ProgressListener listener;

    public interface ProgressListener {
        void onProgress(int percent);
        void onComplete();
        void onError(String message);
    }

    public FileReceiver(String serverIp, int port, String transferId, String rawFileName,
                        long fileSize, File saveDir, byte[] aesKey, byte[] aesIv, String expectedSha256,
                        ProgressListener listener) {
        this.serverIp = serverIp;
        this.port = port;
        this.transferId = transferId;
        this.rawFileName = rawFileName;
        this.fileSize = fileSize;
        this.saveDir = saveDir;
        this.aesKey = aesKey;
        this.aesIv = aesIv;
        this.expectedSha256 = expectedSha256;
        this.listener = listener;
    }

    /** Tệp tạm dùng trong lúc nhận dở; tên cố định theo transferId để lần truyền tiếp sau tìm lại đúng. */
    public File partFile() {
        return new File(saveDir, transferId + ".part");
    }

    @Override
    public void run() {
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            fail("Không tạo được thư mục lưu: " + saveDir);
            return;
        }
        File partFile = partFile();
        boolean complete = false;
        boolean corrupted = false;
        String errorMessage = null;

        try (Socket socket = new Socket(serverIp, port);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {

            // Số byte đã có trên đĩa từ lần (các lần) nhận trước, làm tròn xuống khối 16 byte
            // để điểm nối luôn khớp với bộ đếm AES-CTR của bên gửi.
            long existing = CryptoUtil.alignDown(Math.min(raf.length(), fileSize));
            raf.setLength(existing);
            raf.seek(existing);

            MessageDigest digest = HashUtil.newSha256();
            HashUtil.updateFromFilePrefix(digest, partFile.toPath(), existing);

            // 1. Bắt tay: báo cho relay biết mình đã có sẵn bao nhiêu byte
            Packet.write(out, new Packet(Protocol.CMD_FILE_INIT)
                    .put("transferId", transferId).put("role", Protocol.ROLE_RECEIVER).put("offset", existing));

            // 2. Chờ READY (hoặc ERROR)
            Packet reply = Packet.read(in);
            if (ServerCommands.ERROR.equals(reply.getType())) {
                throw new IOException(reply.get("reason"));
            }
            if (!ServerCommands.READY.equals(reply.getType())) {
                throw new IOException("Phản hồi không đúng từ server: " + reply.getType());
            }
            long offset = reply.getLong("offset", existing);

            // 3. Đọc phần dữ liệu còn thiếu, giải mã AES/CTR rồi ghi tiếp vào tệp tạm
            Cipher cipher = CryptoUtil.createCipher(Cipher.DECRYPT_MODE, aesKey, aesIv, offset);
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long total = offset;
            int lastPercent = fileSize <= 0 ? -1 : (int) (total * 100 / fileSize);
            while (total < fileSize) {
                int want = (int) Math.min(buffer.length, fileSize - total);
                int n = in.read(buffer, 0, want);
                if (n == -1) {
                    break; // mất kết nối hoặc bên gửi hủy
                }
                byte[] plain = cipher.update(buffer, 0, n);
                if (plain != null && plain.length > 0) {
                    raf.write(plain);
                    digest.update(plain);
                }
                total += n;
                int percent = (int) (total * 100 / fileSize);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    if (listener != null) listener.onProgress(percent);
                }
            }
            byte[] tail = cipher.doFinal(); // CTR không đệm nên thường rỗng
            if (tail != null && tail.length > 0) {
                raf.write(tail);
                digest.update(tail);
            }

            // 4. Kiểm tra đủ dung lượng + toàn vẹn SHA-256
            if (total != fileSize) {
                errorMessage = "Nhận file thất bại: bị ngắt kết nối giữa chừng (thiếu "
                        + (fileSize - total) + " bytes). Đã lưu " + total + " byte, có thể bấm gửi lại để truyền tiếp.";
            } else {
                String actualSha256 = HashUtil.toHex(digest.digest());
                if (expectedSha256 != null && !expectedSha256.isBlank()
                        && !expectedSha256.equalsIgnoreCase(actualSha256)) {
                    corrupted = true;
                    errorMessage = "File bị lỗi: sai mã kiểm tra SHA-256 (dữ liệu bị hỏng khi truyền). Vui lòng gửi lại từ đầu.";
                } else {
                    complete = true;
                }
            }
        } catch (IOException e) {
            errorMessage = "Lỗi Socket nhận file: " + e.getMessage();
        } catch (GeneralSecurityException e) {
            errorMessage = "Lỗi giải mã AES: " + e.getMessage();
        }

        if (complete) {
            File finalFile = resolveUniqueFileName(saveDir, rawFileName);
            if (!partFile.renameTo(finalFile)) {
                complete = false;
                errorMessage = "Không đổi tên được tệp tạm sang " + finalFile.getName();
            }
        }
        if (corrupted) {
            partFile.delete(); // dữ liệu sai thì không thể truyền tiếp, phải xóa để lần sau nhận lại từ đầu
        }
        // Lỗi do mất kết nối giữa chừng (không complete, không corrupted): GIỮ LẠI tệp tạm để truyền tiếp.

        if (complete) {
            if (listener != null) listener.onComplete();
        } else if (errorMessage != null) {
            fail(errorMessage);
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
