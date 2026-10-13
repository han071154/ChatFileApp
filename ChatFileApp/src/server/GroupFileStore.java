package server;

import common.CryptoUtil;
import common.Protocol;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Kho file của nhóm (giống "File" trong nhóm Zalo): người gửi tải file lên server MỘT lần,
 * server lưu lại, thành viên nào cần thì bấm tải về lúc nào cũng được.
 *
 * Dữ liệu vẫn đi qua cổng 5001 với đúng cách bắt tay FILE_INIT / READY như truyền file 1-1,
 * chỉ khác là phía bên kia là server chứ không phải một client khác:
 *  - Tải lên:  client là SENDER, server đóng vai RECEIVER và ghi vào group_files/<fileId>.part
 *              (READY.offset = số byte đã có -> tải lên tiếp được khi mạng đứt giữa chừng).
 *  - Tải về:   client là RECEIVER, server đóng vai SENDER và đọc từ group_files/<fileId>.bin
 *              bắt đầu từ offset client báo (tải về tiếp được).
 * Server lưu nguyên bản mã AES/CTR do người gửi mã hóa; khóa AES + SHA-256 chỉ được đưa cho
 * thành viên nhóm khi họ xin tải về. Nhờ vậy client dùng lại nguyên FileSender/FileReceiver.
 *
 * File của nhóm bị xóa khỏi đĩa khi nhóm bị xóa (không còn thành viên) hoặc khi server dừng.
 */
public class GroupFileStore {
    public static final long MAX_FILE_SIZE = 1024L * 1024 * 1024; // 1 GB mỗi file
    private static final int TRANSFER_IDLE_TIMEOUT_MS = 60_000;
    private static final long SESSION_TIMEOUT_SECONDS = 120;   // đã xin phép mà không kết nối 5001

    private final FileRelay relay;
    private final ScheduledExecutorService cleaner;
    private final Path dir = Paths.get("group_files");
    // fileId -> file của nhóm (gồm cả file đang tải lên dở)
    private final Map<String, GroupFile> files = new ConcurrentHashMap<>();
    // transferId -> phiên đã được cho phép, chờ client kết nối cổng 5001
    private final Map<String, Session> pending = new ConcurrentHashMap<>();
    // transferId -> socket đang truyền, để hủy được giữa chừng
    private final Map<String, Session> active = new ConcurrentHashMap<>();
    private volatile ChatServer server;

    GroupFileStore(FileRelay relay, ScheduledExecutorService cleaner) {
        this.relay = relay;
        this.cleaner = cleaner;
        deleteStoredFiles(); // file còn sót từ lần chạy trước không còn nhóm nào sở hữu
    }

    /** ChatServer gắn vào để kho file thông báo được cho nhóm khi có file mới. */
    void setServer(ChatServer server) {
        this.server = server;
    }

    // ================== Gọi từ ClientHandler (kênh chat) ==================

    /**
     * Cho phép tải file lên nhóm. Gọi lại với cùng transferId (= fileId) để tải lên tiếp phần còn thiếu.
     * Trả về null nếu hợp lệ, ngược lại trả về lý do từ chối.
     */
    String authorizeUpload(String transferId, String user, InetAddress address, String group,
                           String fileName, long fileSize, String sha256, String aesKey, String aesIv) {
        if (fileSize <= 0) {
            return "Dung lượng file không hợp lệ";
        }
        if (fileSize > MAX_FILE_SIZE) {
            return "File quá lớn, tối đa " + (MAX_FILE_SIZE / 1024 / 1024) + " MB";
        }
        if (aesKey == null || aesIv == null || sha256 == null) {
            return "Thiếu khóa AES hoặc mã SHA-256";
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return "Server không tạo được thư mục lưu file";
        }
        GroupFile candidate = new GroupFile(transferId, group, user, fileName, fileSize, sha256, aesKey, aesIv);
        GroupFile file = files.putIfAbsent(transferId, candidate);
        if (file == null) {
            file = candidate;
        } else if (file.ready || !file.uploader.equals(user) || !file.group.equals(group) || file.fileSize != fileSize) {
            return "transferId đã được dùng cho file khác";
        }
        Session previous = active.get(transferId);
        if (previous != null) {
            previous.close(); // kết nối tải lên cũ (đã đứt phía client) chưa bị phát hiện: bỏ để tải lên tiếp
        }
        FileRelay.TransferStatus status = relay.track(transferId, user, "Nhóm " + group, fileName, fileSize);
        authorize(new Session(transferId, Protocol.ROLE_SENDER, user, address, file, status));
        return null;
    }

    /** Cho phép một thành viên tải file về. Trả về file nếu hợp lệ (để gửi khóa AES cho client), null nếu không. */
    GroupFile authorizeDownload(String transferId, String user, InetAddress address, String group, String fileId) {
        GroupFile file = fileId == null ? null : files.get(fileId);
        if (file == null || !file.ready || !file.group.equals(group) || files.containsKey(transferId)
                || active.containsKey(transferId)) {
            return null;
        }
        Session old = pending.get(transferId);
        if (old != null && !old.user.equals(user)) {
            return null; // transferId đang thuộc về người khác
        }
        FileRelay.TransferStatus status = relay.track(transferId, "Nhóm " + group, user, file.fileName, file.fileSize);
        authorize(new Session(transferId, Protocol.ROLE_RECEIVER, user, address, file, status));
        return file;
    }

    private void authorize(Session session) {
        session.status.setState("Chờ kết nối");
        pending.put(session.transferId, session);
        cleaner.schedule(() -> {
            if (pending.remove(session.transferId, session)) {
                session.status.finish("Hết thời gian chờ");
            }
        }, SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Hủy phiên tải lên/tải về của user. Hủy tải lên thì bỏ luôn phần đã tải. Trả về false nếu không có phiên. */
    boolean cancel(String transferId, String user) {
        Session session = pending.get(transferId);
        if (session != null && session.user.equals(user)) {
            pending.remove(transferId, session);
        } else {
            session = active.get(transferId);
            if (session == null || !session.user.equals(user)) {
                GroupFile file = files.get(transferId);
                if (file == null || file.ready || !file.uploader.equals(user)) {
                    return false;
                }
                discard(file); // tải lên dở, đang không kết nối: chỉ cần xóa phần đã có
                return true;
            }
            session.cancelled = true;
            session.close();
        }
        session.status.finish("Đã hủy");
        if (Protocol.ROLE_SENDER.equals(session.role)) {
            discard(session.file);
        }
        return true;
    }

    /** Danh sách file đã tải lên xong của nhóm, cũ trước mới sau (gửi cho người mới vào nhóm). */
    List<GroupFile> listFiles(String group) {
        List<GroupFile> list = new ArrayList<>();
        for (GroupFile f : files.values()) {
            if (f.ready && f.group.equals(group)) {
                list.add(f);
            }
        }
        list.sort(Comparator.comparing(f -> f.createdAt));
        return list;
    }

    /** Nhóm bị xóa: xóa mọi file của nhóm, kể cả file đang tải lên dở. */
    void removeGroup(String group) {
        for (GroupFile f : new ArrayList<>(files.values())) {
            if (f.group.equals(group)) {
                discard(f);
            }
        }
    }

    void stop() {
        for (Session s : active.values()) {
            s.cancelled = true;
            s.close();
        }
        active.clear();
        pending.clear();
        files.clear();
        deleteStoredFiles();
    }

    // ================== Kết nối cổng 5001 (gọi từ FileRelay) ==================

    /** Trả về true nếu transferId là phiên của kho file nhóm (khi đó kết nối do lớp này xử lý xong). */
    boolean handles(String transferId) {
        return transferId != null && pending.containsKey(transferId);
    }

    /** Xử lý trọn một kết nối 5001 đã đọc xong gói FILE_INIT. Chạy trên luồng của Thread Pool FileRelay. */
    void handleConnection(Socket socket, DataInputStream in, DataOutputStream out, Packet init) throws IOException {
        String transferId = init.get("transferId");
        Session session = pending.get(transferId);
        String reason = null;
        if (session == null) {
            reason = "Phiên truyền không tồn tại hoặc đã hết hạn";
        } else if (!session.role.equals(init.get("role"))) {
            reason = "role không đúng với phiên " + (Protocol.ROLE_SENDER.equals(session.role) ? "tải lên" : "tải về");
        } else if (session.address != null && !session.address.equals(socket.getInetAddress())) {
            reason = "Địa chỉ IP không khớp với người dùng của phiên";
        } else if (!pending.remove(transferId, session)) {
            reason = "Phiên truyền đã được dùng";
        }
        if (reason != null) {
            ServerLogger.warn("Kho file nhóm từ chối " + socket.getRemoteSocketAddress() + ": " + reason);
            Packet.write(out, new Packet(ServerCommands.ERROR).put("reason", reason));
            closeQuietly(socket);
            return;
        }

        session.socket = socket;
        active.put(transferId, session);
        try {
            if (Protocol.ROLE_SENDER.equals(session.role)) {
                receiveUpload(session, in, out);
            } else {
                sendDownload(session, out, init.getLong("offset", 0));
            }
        } finally {
            active.remove(transferId, session);
            closeQuietly(socket);
        }
    }

    /** Server đóng vai người nhận: ghi bản mã vào tệp .part, đủ dung lượng thì công bố cho nhóm. */
    private void receiveUpload(Session s, DataInputStream in, DataOutputStream out) {
        GroupFile f = s.file;
        long start = System.currentTimeMillis();
        long offset = 0;
        long total = 0;
        String result;
        try (RandomAccessFile raf = new RandomAccessFile(f.partPath(dir).toFile(), "rw")) {
            offset = CryptoUtil.alignDown(Math.min(raf.length(), f.fileSize));
            raf.setLength(offset);
            raf.seek(offset);
            total = offset;
            Packet.write(out, new Packet(ServerCommands.READY).put("transferId", s.transferId).put("offset", offset));
            ServerLogger.info("[" + s.transferId + "] '" + s.user + "' bắt đầu tải '" + f.fileName + "' lên nhóm '"
                    + f.group + "'" + (offset > 0 ? " (tiếp từ byte " + offset + ")" : ""));
            s.status.setState("Đang tải lên");
            s.status.setBytes(offset);

            s.socket.setSoTimeout(TRANSFER_IDLE_TIMEOUT_MS);
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            while (total < f.fileSize) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, f.fileSize - total));
                if (n == -1) {
                    break;
                }
                raf.write(buffer, 0, n);
                total += n;
                s.status.setBytes(total);
            }
            result = total == f.fileSize ? "Hoàn tất" : "Mất kết nối (đã nhận " + total + "/" + f.fileSize + " bytes)";
        } catch (SocketTimeoutException e) {
            result = "Người gửi không phản hồi quá " + TRANSFER_IDLE_TIMEOUT_MS / 1000 + " giây";
        } catch (IOException e) {
            result = s.cancelled ? "Đã hủy" : "Mất kết nối (" + e.getMessage() + ")";
        }

        if ("Hoàn tất".equals(result)) {
            try {
                Files.move(f.partPath(dir), f.dataPath(dir), StandardCopyOption.REPLACE_EXISTING);
                f.ready = true;
            } catch (IOException e) {
                result = "Không lưu được file: " + e.getMessage();
            }
        }
        s.status.finish(result);
        logSpeed(s.transferId, result, total - offset, start);
        if (!f.ready) {
            return; // giữ lại tệp .part để người gửi tải lên tiếp
        }
        if (files.get(f.id) != f) {
            discard(f); // nhóm đã bị xóa trong lúc đang tải lên
            return;
        }
        ServerLogger.info("Nhóm '" + f.group + "' có file mới '" + f.fileName + "' từ '" + f.uploader + "'");
        ChatServer srv = server;
        if (srv != null) {
            srv.sendToGroup(f.group, fileAnnouncement(f));
        }
    }

    /** Server đóng vai người gửi: đọc bản mã từ đĩa, bắt đầu tại offset client đã có. */
    private void sendDownload(Session s, DataOutputStream out, long requestedOffset) {
        GroupFile f = s.file;
        long start = System.currentTimeMillis();
        long offset = Math.max(0, Math.min(requestedOffset, f.fileSize));
        long sent = 0;
        String result;
        try (RandomAccessFile raf = new RandomAccessFile(f.dataPath(dir).toFile(), "r")) {
            Packet.write(out, new Packet(ServerCommands.READY).put("transferId", s.transferId).put("offset", offset));
            s.status.setState("Đang tải về");
            s.status.setBytes(offset);
            raf.seek(offset);
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            int n;
            while ((n = raf.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                sent += n;
                s.status.setBytes(offset + sent);
            }
            out.flush();
            s.socket.shutdownOutput();
            result = "Hoàn tất";
        } catch (IOException e) {
            result = s.cancelled ? "Đã hủy" : "Mất kết nối (" + e.getMessage() + ")";
        }
        s.status.finish(result);
        logSpeed(s.transferId, result, sent, start);
    }

    // ================== Tiện ích ==================

    /** Gói tin báo cho thành viên nhóm có file mới: GROUP_FILE {group, fileId, from, fileName, fileSize, time}. */
    static Packet fileAnnouncement(GroupFile f) {
        return new Packet(ServerCommands.GROUP_FILE)
                .put("group", f.group)
                .put("fileId", f.id)
                .put("from", f.uploader)
                .put("fileName", f.fileName)
                .put("fileSize", f.fileSize)
                .put("time", f.createdAt.toString());
    }

    private void discard(GroupFile f) {
        files.remove(f.id, f);
        Session s = active.get(f.id);
        if (s != null) {
            s.cancelled = true;
            s.close();
        }
        deleteQuietly(f.partPath(dir));
        deleteQuietly(f.dataPath(dir));
    }

    private void deleteStoredFiles() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> list = Files.list(dir)) {
            list.forEach(GroupFileStore::deleteQuietly);
        } catch (IOException e) {
            ServerLogger.warn("Không dọn được thư mục " + dir.toAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            path.toFile().deleteOnExit(); // Windows: file đang được người khác tải về, xóa khi tắt server
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void logSpeed(String transferId, String result, long bytes, long startTime) {
        long ms = Math.max(1, System.currentTimeMillis() - startTime);
        ServerLogger.info(String.format("[%s] %s: %,d bytes trong %.1f s (%.2f MB/s)",
                transferId, result, bytes, ms / 1000.0, bytes / 1024.0 / 1024.0 / (ms / 1000.0)));
    }

    // ================== Dữ liệu ==================

    /** Một file đã (hoặc đang) được tải lên nhóm. id cũng là transferId của lần tải lên. */
    static final class GroupFile {
        final String id;
        final String group;
        final String uploader;
        final String fileName;
        final long fileSize;
        final String sha256;
        final String aesKey; // Base64
        final String aesIv;  // Base64
        final LocalDateTime createdAt = LocalDateTime.now();
        volatile boolean ready;

        GroupFile(String id, String group, String uploader, String fileName, long fileSize,
                  String sha256, String aesKey, String aesIv) {
            this.id = id;
            this.group = group;
            this.uploader = uploader;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.sha256 = sha256;
            this.aesKey = aesKey;
            this.aesIv = aesIv;
        }

        Path partPath(Path dir) {
            return dir.resolve(id + ".part");
        }

        Path dataPath(Path dir) {
            return dir.resolve(id + ".bin");
        }
    }

    private static final class Session {
        final String transferId;
        final String role; // vai của CLIENT: SENDER = tải lên, RECEIVER = tải về
        final String user;
        final InetAddress address;
        final GroupFile file;
        final FileRelay.TransferStatus status;
        volatile Socket socket;
        volatile boolean cancelled;

        Session(String transferId, String role, String user, InetAddress address, GroupFile file,
                FileRelay.TransferStatus status) {
            this.transferId = transferId;
            this.role = role;
            this.user = user;
            this.address = address;
            this.file = file;
            this.status = status;
        }

        void close() {
            Socket s = socket;
            if (s != null) {
                closeQuietly(s);
            }
        }
    }
}
