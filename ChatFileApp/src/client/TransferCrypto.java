package client;

/**
 * Khóa AES + IV của một phiên gửi file, cùng mã SHA-256 của toàn bộ file đã tính trước khi gửi.
 * Được giữ lại ở phía gửi cho tới khi phiên hoàn tất hoặc bị hủy hẳn, để nếu mạng gián đoạn
 * giữa chừng thì có thể dùng lại CHÍNH các giá trị này mà gửi lại FILE_REQ, cho phép bên nhận
 * ghép đúng vào phần đã nhận dở (truyền tiếp) thay vì phải truyền lại từ đầu.
 */
record TransferCrypto(byte[] aesKey, byte[] aesIv, String sha256) {
}
