# \# ỨNG DỤNG CHAT \& TRUYỀN TỆP TIN 1-1 (JAVA SOCKET)

# 

# Đồ án môn học: Lập trình Mạng máy tính  

# Kiến trúc: Client - Server (Multi-threaded TCP Sockets)  

# Ngôn ngữ \& Công cụ: Java (JDK 17), Java Swing, Apache NetBeans

# 

# \---

# 

# \## 1. Thành viên nhóm \& Phân công nhiệm vụ

# 

# | Thành viên | Trách nhiệm chính | Mô tả công việc chi tiết |

# | :--- | :--- | :--- |

# | \*\*Thảo\*\* | \*\*Server Core\*\* | - Xây dựng `ChatServer`: mở `ServerSocket`, xử lý đa luồng kết nối client (`ClientHandler`).<br>- Quản lý danh sách người dùng online (`ConcurrentHashMap`).<br>- Định tuyến tin nhắn chat 1-1, thông báo trạng thái user đăng nhập/thoát.<br>- Xây dựng `FileRelay` trên port 5001: ghép cặp người gửi/nhận qua `transferId` và ghi log hệ thống. |

# | \*\*Hân\*\* | \*\*Client \& GUI\*\* | - Thiết kế giao diện Java Swing (`ChatFrame`): màn hình đăng nhập, danh sách bạn bè, khung chat, nút gửi file, thanh tiến trình `JProgressBar`.<br>- Xây dựng `ChatClient`: luồng đọc tin nhắn liên tục không làm đơ giao diện.<br>- Hiển thị hộp thoại (Dialog) xác nhận nhận hoặc từ chối file. |

# | \*\*Anh\*\* | \*\*Giao thức \& Truyền file\*\* | - Thiết kế đặc tả giao thức `Protocol.java` (định dạng bản tin, port chuẩn).<br>- Lập trình `FileSender.java` và `FileReceiver.java`: truyền nhận theo chunk 64KB, tính % tiến độ, xử lý lỗi file rỗng, trùng tên file (`(1).ext`), đứt kết nối.<br>- Lập kịch bản kiểm thử file lớn (> 100 MB), viết tài liệu `README.md` và quản lý Git. |

# 

# \---

# 

# \## 2. Kiến trúc \& Giao thức truyền thông (Protocol)

# 

# Hệ thống tách biệt thành 2 kênh socket độc lập để đảm bảo việc truyền file lớn không làm nghẽn luồng chat:

# 

# \* \*\*Chat Port (`5000`):\*\* Kênh trao đổi chuỗi văn bản UTF-8 (kết thúc bằng `\\n`), phân cách bằng ký tự `|`.

# &#x20; \* `LOGIN|<username>`: Đăng nhập.

# &#x20; \* `LOGIN\_SUCCESS` / `LOGIN\_FAILED|<reason>`: Phản hồi đăng nhập.

# &#x20; \* `USER\_LIST|<user1>,<user2>,...`: Cập nhật danh sách online.

# &#x20; \* `MSG|<toUser>|<content>`: Gửi tin nhắn cá nhân 1-1.

# &#x20; \* `FILE\_REQ|<toUser>|<transferId>|<fileName>|<fileSize>`: Yêu cầu gửi file.

# &#x20; \* `FILE\_RESP|<toUser>|<transferId>|<ACCEPT/REJECT>`: Người nhận đồng ý hoặc từ chối.

# \* \*\*File Relay Port (`5001`):\*\* Kênh truyền dữ liệu nhị phân (Binary Stream).

# &#x20; \* Client gửi lệnh mở đầu: `FILE\_INIT|<transferId>|<SENDER/RECEIVER>\\n`.

# &#x20; \* Sau khi Server báo `READY\\n`, `FileSender` đẩy dữ liệu nhị phân theo từng chunk 64KB (`Protocol.BUFFER\_SIZE`).

# 

# \---

# 

# \## 3. Cấu trúc mã nguồn

# 

# ```text

# ChatFileApp/

# ├── src/

# │   ├── common/

# │   │   ├── Protocol.java       # Bộ hằng số, port và cấu trúc bản tin giao thức

# │   │   ├── FileInfo.java       # Model lưu thông tin tệp tin

# │   │   └── Message.java        # Model bản tin

# │   ├── server/

# │   │   ├── ChatServer.java     # Lắng nghe kết nối chat (Port 5000)

# │   │   ├── ClientHandler.java  # Xử lý luồng riêng cho từng client

# │   │   └── FileRelay.java      # Ghép cặp luồng gửi/nhận file (Port 5001)

# │   └── client/

# │       ├── ChatClient.java     # Kết nối chat phía client

# │       ├── ChatFrame.java      # Giao diện Swing chính

# │       ├── FileSender.java     # Luồng gửi file theo chunk \& cập nhật tiến độ

# │       └── FileReceiver.java   # Luồng nhận file, kiểm tra dung lượng \& chống trùng tên

# └── README.md

