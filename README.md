# Ứng dụng Chat & Truyền tệp tin 1-1 (Java Socket)

Đồ án môn **Lập trình Mạng máy tính** – Nhóm 2, lớp 23DTHD1.

- **Kiến trúc:** Client – Server (TCP Socket, mỗi client một luồng trong thread pool)
- **Ngôn ngữ và công cụ:** Java (JDK 22), Java Swing, Apache NetBeans 22, Git/GitHub

## 1. Chức năng

- Đăng nhập bằng tên, danh sách người dùng online cập nhật theo thời gian thực.
- Chat 1-1 và chat nhóm (tạo nhóm, vào nhóm, rời nhóm).
- Gửi tệp 1-1: người nhận chọn **Nhận / Từ chối**, có thanh tiến trình, hủy giữa chừng, tự đổi tên khi trùng (`ten (1).ext`).
- Gửi tệp trong nhóm: tải lên server, thành viên bấm **Tải về** khi cần.
- **Truyền tiếp (resume)** khi đứt kết nối: bên nhận giữ tệp tạm `.part` và báo số byte đã có, bên gửi gửi tiếp từ đó.
- **Mã hóa AES-256:** tin nhắn dùng AES/GCM (khóa dẫn xuất bằng PBKDF2), luồng tệp dùng AES/CTR.
- **Kiểm tra toàn vẹn SHA-256** sau khi nhận tệp.
- Server có giao diện quản lý (`ServerFrame`): danh sách user, nhóm, phiên truyền tệp, nhật ký; ghi log ra `server.log`.

## 2. Thành viên và bảng đóng góp

| MSSV | Họ và Tên | Công việc đã thực hiện | Merge Requests |
|---|---|---|---|
| 2380600607 | Phạm Ngọc Hân | Client và giao diện Swing (ChatFrame, ChatClient), mã hóa AES cho tin nhắn, dọn repo, viết lại README | Commit trực tiếp trước khi áp dụng quy trình PR: 6e4cf05, fde2f12, 311512c. Sau đó: #(số PR dọn repo), #(số PR README) |
| 2380600077 | Tăng Nguyễn Kiều Anh | Giao thức (Protocol), FileSender / FileReceiver, AES, SHA-256, truyền tiếp, README bản đầu tiên | #3 |
| 2380602066 | Nguyễn Thị Thảo | Server (ChatServer, ClientHandler, FileRelay, ServerLogger), ServerFrame, gửi tệp trong nhóm | #1, #2, #4 |

## 3. Kiến trúc và giao thức

Hệ thống dùng **hai kênh TCP độc lập** để truyền tệp lớn không làm nghẽn tin nhắn:

- **Cổng 5000 – kênh chat và điều khiển** (`ChatServer`).
- **Cổng 5001 – kênh dữ liệu tệp** (`FileRelay`): server ghép người gửi và người nhận theo `transferId` rồi chuyển tiếp từng khối 64 KB.

### Khung gói tin

Mỗi gói tin gồm **4 byte độ dài (int)** theo sau là **nội dung JSON (UTF-8)**, tối đa 1 MB. Bên nhận đọc 4 byte rồi dùng `readFully` để đọc đủ số byte đó, nên không bị vỡ hoặc dính gói khi mạng chia nhỏ dữ liệu.

```json
{ "type": "MSG", "to": "han", "content": "ENC1:..." }
```

### Các loại gói tin chính

| Nhóm | Gói tin |
|---|---|
| Phiên | `LOGIN`, `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGOUT`, `USER_LIST`, `USER_JOINED`, `USER_LEFT`, `ERROR` |
| Chat | `MSG`, `GROUP_CREATE`, `GROUP_JOIN`, `GROUP_LEAVE`, `GROUP_MSG`, `GROUP_LIST`, `GROUP_UPDATE` |
| Tệp 1-1 | `FILE_REQ`, `FILE_RESP` (`ACCEPT` / `REJECT`), `FILE_CANCEL` |
| Tệp nhóm | `GROUP_FILE_UPLOAD`, `GROUP_FILE_UPLOAD_OK`, `GROUP_FILE`, `GROUP_FILE_DOWNLOAD`, `GROUP_FILE_DOWNLOAD_OK` |
| Kênh 5001 | `FILE_INIT` (vai trò `SENDER` / `RECEIVER`, kèm `offset` khi truyền tiếp), `READY` |

### Luồng gửi tệp 1-1

1. Người gửi tính SHA-256, sinh khóa AES, gửi `FILE_REQ` qua cổng 5000.
2. Người nhận chọn Nhận hoặc Từ chối, trả `FILE_RESP`.
3. Nếu đồng ý, hai bên cùng gửi `FILE_INIT` tới cổng 5001; server ghép cặp và báo `READY`.
4. Người gửi mã hóa và gửi từng khối 64 KB, người nhận giải mã và ghi vào tệp `.part`.
5. Người nhận so SHA-256; khớp thì đổi tên `.part` thành tên tệp thật.

## 4. Cấu trúc mã nguồn

```text
ChatFileApp/src/
├── common/
│   ├── Protocol.java        # Hằng số: cổng, tên lệnh, kích thước khối
│   ├── CryptoUtil.java      # AES-256 (CTR cho tệp, GCM cho tin nhắn), PBKDF2
│   ├── HashUtil.java        # SHA-256
│   ├── FileInfo.java        # Thông tin tệp
│   └── Message.java         # Bản tin hiển thị
├── server/
│   ├── ChatServer.java      # Nhận kết nối chat (cổng 5000), thread pool
│   ├── ClientHandler.java   # Xử lý một client: đăng nhập, chat, nhóm
│   ├── FileRelay.java       # Ghép cặp và chuyển tiếp tệp (cổng 5001)
│   ├── GroupFileStore.java  # Kho tệp nhóm trên server
│   ├── Packet.java          # Đóng/mở khung 4 byte + JSON
│   ├── ServerCommands.java  # Hằng số lệnh phía server
│   ├── ServerLogger.java    # Ghi log console và server.log
│   ├── ServerFrame.java     # Giao diện quản lý server
│   └── ServerTestClient.java# Công cụ tự kiểm thử server
└── client/
    ├── ClientApp.java       # Điểm khởi động client
    ├── ChatFrame.java       # Giao diện Swing chính
    ├── ChatClient.java      # Kết nối, luồng đọc tin
    ├── FileSender.java      # Gửi tệp theo khối, mã hóa AES
    ├── FileReceiver.java    # Nhận tệp, giải mã, SHA-256, truyền tiếp
    └── TransferCrypto.java  # Gói khóa AES, IV và SHA-256 của một phiên
```

## 5. Cách chạy

1. Cài **JDK 22** và Apache NetBeans.
2. Mở project `ChatFileApp` trong NetBeans, chọn **Clean and Build**.
3. **Máy server:** chạy `server.ServerFrame` (giao diện quản lý) hoặc `server.ChatServer`, bấm **Bắt đầu**. Mở cổng TCP **5000** và **5001** trên tường lửa.
4. **Máy client:** chạy `client.ClientApp`, nhập IP của máy server và tên đăng nhập.
5. Các máy khác mạng có thể dùng mạng ảo (ví dụ ZeroTier) rồi nhập IP ảo của server.

## 6. Quy trình làm việc trên Git

Repo được tạo trước khi có hướng dẫn quy trình Git của giảng viên, nên lịch sử đầu có commit trực tiếp lên `main`. Từ ngày **[điền ngày bắt đầu áp dụng]**, nhóm làm theo quy trình:

- Mỗi đầu việc là một Issue (gán một người, có nhãn và Milestone).
- Nhánh đặt tên `feature/<MSSV>-issue-<số>-<tên>`; không push trực tiếp lên `main`.
- Commit theo dạng `feat | fix | refactor | docs: mô tả`.
- Gộp bằng Pull Request, có ít nhất một thành viên khác xem và Approve.