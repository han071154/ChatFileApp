package common;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã hóa đối xứng AES-256/CTR cho luồng dữ liệu file trước khi ghi vào Socket TCP.
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

    public static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decodeBase64(String data) {
        return Base64.getDecoder().decode(data);
    }
}
