package common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Băm SHA-256 để kiểm tra tính toàn vẹn file: bên nhận so sánh mã băm tính được sau khi ghi
 * xong với mã băm bên gửi tính trước khi truyền, nhờ đó phát hiện file bị lỗi/thiếu do mạng.
 */
public final class HashUtil {
    private HashUtil() {
    }

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Thiếu thuật toán SHA-256 trong JVM", e);
        }
    }

    /** Băm toàn bộ nội dung file (đọc tuần tự một lượt). */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        return toHex(digest.digest());
    }

    /**
     * Cập nhật digest bằng đúng {@code length} byte đầu của file. Dùng khi truyền tiếp (resume)
     * để khôi phục đúng trạng thái băm của phần dữ liệu đã nhận ở lần trước, trước khi băm tiếp
     * phần dữ liệu mới nhận thêm.
     */
    public static void updateFromFilePrefix(MessageDigest digest, Path file, long length) throws IOException {
        if (length <= 0) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int n = in.read(buffer, 0, want);
                if (n == -1) {
                    break;
                }
                digest.update(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
