package common;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã hóa đối xứng AES-256 cho dữ liệu trước khi ghi vào Socket TCP:
 *  - Luồng file: AES/CTR (cho phép nhảy tới khối bất kỳ để truyền tiếp).
 *  - Tin nhắn chat: AES/GCM, khóa dẫn xuất từ mật khẩu chung bằng PBKDF2 (xem encryptText/decryptText).
 *
 *
 * CTR biến AES thành mật mã dòng: không cần padding và keystream của khối thứ N chỉ phụ
 * thuộc vào (khóa, IV ban đầu + N). Nhờ vậy có thể "nhảy" thẳng tới khối bất kỳ bằng cách
 * cộng thêm chỉ số khối vào bộ đếm 128-bit ban đầu - đây là cơ chế cho phép truyền tiếp
 * (resume) hoạt động đúng ngay cả khi dữ liệu trên đường truyền đã được mã hóa.
 */
public final class CryptoUtil {
    public static final String ALGORITHM = "AES";
    public static final String TRANSFORMATION = "AES/CTR/NoPadding";
    public static final int KEY_SIZE_BITS = 256;
    public static final int BLOCK_SIZE = 16; // byte, cũng là kích thước khối đếm của CTR

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    /** Sinh khóa AES-256 ngẫu nhiên cho một phiên truyền file. */
    public static byte[] generateKeyBytes() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance(ALGORITHM);
            kg.init(KEY_SIZE_BITS, RANDOM);
            return kg.generateKey().getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Thiếu thuật toán AES trong JVM", e);
        }
    }

    /** Sinh IV/bộ đếm ban đầu 16 byte, dùng chung cho cả mã hóa lẫn giải mã của một phiên. */
    public static byte[] generateIv() {
        byte[] iv = new byte[BLOCK_SIZE];
        RANDOM.nextBytes(iv);
        return iv;
    }

    /**
     * Tạo Cipher AES/CTR đã được "chỉnh" tới đúng khối ứng với byteOffset (ENCRYPT lẫn DECRYPT
     * dùng chung hàm này vì CTR đối xứng: giải mã cũng là XOR keystream). byteOffset phải là bội
     * số của {@link #BLOCK_SIZE}; FileSender/FileReceiver đảm bảo điều này khi truyền tiếp.
     */
    public static Cipher createCipher(int mode, byte[] keyBytes, byte[] ivBytes, long byteOffset)
            throws GeneralSecurityException {
        if (byteOffset % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("Offset truyền tiếp phải là bội số của " + BLOCK_SIZE + " byte: " + byteOffset);
        }
        long blockIndex = byteOffset / BLOCK_SIZE;
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        SecretKey key = new SecretKeySpec(keyBytes, ALGORITHM);
        IvParameterSpec ivSpec = new IvParameterSpec(advanceCounter(ivBytes, blockIndex));
        cipher.init(mode, key, ivSpec);
        return cipher;
    }

    /** Cộng blockIndex vào bộ đếm 128-bit big-endian của IV (có tràn số, mod 2^128). */
    private static byte[] advanceCounter(byte[] iv, long blockIndex) {
        BigInteger counter = new BigInteger(1, iv);
        BigInteger mod = BigInteger.ONE.shiftLeft(8 * BLOCK_SIZE);
        BigInteger advanced = counter.add(BigInteger.valueOf(blockIndex)).mod(mod);
        byte[] raw = advanced.toByteArray();
        byte[] result = new byte[BLOCK_SIZE];
        int copyLen = Math.min(raw.length, BLOCK_SIZE);
        System.arraycopy(raw, raw.length - copyLen, result, BLOCK_SIZE - copyLen, copyLen);
        return result;
    }

    /** Làm tròn xuống bội số gần nhất của {@link #BLOCK_SIZE} (dùng khi chốt điểm truyền tiếp). */
    public static long alignDown(long byteOffset) {
        return (byteOffset / BLOCK_SIZE) * BLOCK_SIZE;
    }

    // ---------- Mã hóa tin nhắn chat: AES-256/GCM, khóa dẫn xuất từ mật khẩu chung ----------

    /** Tiền tố đánh dấu một nội dung chat đã được mã hóa (để phân biệt với văn bản thường). */
    public static final String MSG_PREFIX = "ENC1:";
    /** Mật khẩu mặc định khi người dùng không nhập "Khóa mã hóa" lúc đăng nhập. */
    public static final String DEFAULT_PASSPHRASE = "ChatFileApp";
    private static final String MSG_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 65536;
    private static final byte[] MSG_SALT = "ChatFileApp.message.v1".getBytes(StandardCharsets.UTF_8);

    /** Dẫn xuất khóa AES-256 từ mật khẩu chung (mọi client cùng mật khẩu sẽ ra cùng một khóa). */
    public static byte[] deriveKey(String passphrase) {
        try {
            String p = (passphrase == null || passphrase.isEmpty()) ? DEFAULT_PASSPHRASE : passphrase;
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(p.toCharArray(), MSG_SALT, PBKDF2_ITERATIONS, KEY_SIZE_BITS)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không dẫn xuất được khóa AES từ mật khẩu", e);
        }
    }

    public static boolean isEncrypted(String content) {
        return content != null && content.startsWith(MSG_PREFIX);
    }

    /** Mã hóa một chuỗi: trả về "ENC1:" + Base64(IV 12 byte + bản mã + thẻ xác thực GCM). Mỗi tin dùng IV ngẫu nhiên mới. */
    public static String encryptText(byte[] keyBytes, String plain) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(MSG_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(enc, 0, all, iv.length, enc.length);
        return MSG_PREFIX + encodeBase64(all);
    }

    /** Giải mã chuỗi do encryptText tạo ra. Ném GeneralSecurityException nếu sai khóa hoặc dữ liệu bị sửa (GCM tự kiểm tra). */
    public static String decryptText(byte[] keyBytes, String encoded) throws GeneralSecurityException {
        if (!isEncrypted(encoded)) {
            throw new GeneralSecurityException("Nội dung không có tiền tố mã hóa");
        }
        byte[] all = decodeBase64(encoded.substring(MSG_PREFIX.length()));
        if (all.length <= GCM_IV_BYTES) {
            throw new GeneralSecurityException("Dữ liệu mã hóa quá ngắn");
        }
        Cipher cipher = Cipher.getInstance(MSG_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM),
                new GCMParameterSpec(GCM_TAG_BITS, all, 0, GCM_IV_BYTES));
        byte[] plain = cipher.doFinal(all, GCM_IV_BYTES, all.length - GCM_IV_BYTES);
        return new String(plain, StandardCharsets.UTF_8);
    }

    public static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decodeBase64(String data) {
        return Base64.getDecoder().decode(data);
    }
}