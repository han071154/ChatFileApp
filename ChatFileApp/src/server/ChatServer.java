package server;

import common.Protocol;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server chat (cổng 5000).
 * - Mỗi kết nối được giao cho một ClientHandler chạy trong Thread Pool (ExecutorService)
 *   có giới hạn số luồng; khi pool đầy thì từ chối kết nối mới thay vì tạo luồng vô hạn.
 * - Quản lý danh sách user online và danh sách nhóm chat.
 * - Khởi động FileRelay (cổng 5001) chạy song song.
 */
public class ChatServer {
    public static final int MAX_CLIENTS = 50;

    // username -> handler của client đang online
    private final Map<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();
    // tên nhóm -> tập username thành viên
    private final Map<String, Set<String>> groups = new ConcurrentHashMap<>();

    private final int port;
    private final FileRelay fileRelay;
    private final ThreadPoolExecutor clientPool;
    private volatile boolean running = true;

    public ChatServer(int port, FileRelay fileRelay) {
        this.port = port;
        this.fileRelay = fileRelay;
        // SynchronousQueue: không xếp hàng chờ, quá MAX_CLIENTS luồng thì ném RejectedExecutionException
        this.clientPool = new ThreadPoolExecutor(
                0, MAX_CLIENTS, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), namedThreadFactory("chat"));
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            ServerLogger.info("ChatServer đang lắng nghe tại cổng " + port + " (tối đa " + MAX_CLIENTS + " client)");
            while (running) {
                Socket socket = serverSocket.accept();
                try {
                    clientPool.execute(new ClientHandler(socket, this));
                } catch (RejectedExecutionException e) {
                    ServerLogger.warn("Server đầy, từ chối kết nối từ " + socket.getRemoteSocketAddress());
                    rejectConnection(socket);
                }
            }
        } catch (IOException e) {
            ServerLogger.error("ChatServer dừng do lỗi: " + e.getMessage());
        } finally {
            clientPool.shutdownNow();
        }
    }

    private void rejectConnection(Socket socket) {
        try (socket) {
            Packet.write(new DataOutputStream(socket.getOutputStream()),
                    new Packet(Protocol.CMD_LOGIN_FAIL).put("reason", "Server đã đầy, vui lòng thử lại sau"));
        } catch (IOException ignored) {
        }
    }

    public FileRelay getFileRelay() {
        return fileRelay;
    }

    // ================== Quản lý user online ==================

    /** Trả về true nếu đăng ký thành công (tên chưa có ai dùng). */
    public boolean registerUser(String username, ClientHandler handler) {
        return onlineUsers.putIfAbsent(username, handler) == null;
    }

    public void unregisterUser(String username, ClientHandler handler) {
        // remove(key, value): chỉ xóa nếu đúng handler này (tránh xóa nhầm phiên khác)
        if (!onlineUsers.remove(username, handler)) {
            return;
        }
        for (String group : new ArrayList<>(groups.keySet())) {
            leaveGroup(group, username);
        }
        broadcast(new Packet(ServerCommands.USER_LEFT).put("username", username), null);
        broadcastUserList();
    }

    public ClientHandler getUser(String username) {
        return onlineUsers.get(username);
    }

    public void broadcast(Packet packet, String exceptUser) {
        for (ClientHandler h : onlineUsers.values()) {
            if (!h.getUsername().equals(exceptUser)) {
                h.send(packet);
            }
        }
    }

    public void broadcastUserList() {
        broadcast(new Packet(Protocol.CMD_USER_LIST).put("users", joinSorted(onlineUsers.keySet())), null);
    }

    // ================== Chat nhóm ==================

    /** Tạo nhóm mới, người tạo tự động là thành viên. Trả về false nếu tên nhóm đã tồn tại. */
    public boolean createGroup(String group, String creator) {
        Set<String> members = ConcurrentHashMap.newKeySet();
        members.add(creator);
        if (groups.putIfAbsent(group, members) != null) {
            return false;
        }
        broadcastGroupList();
        sendGroupUpdate(group);
        return true;
    }

    /** Trả về false nếu nhóm không tồn tại. */
    public boolean joinGroup(String group, String username) {
        // computeIfPresent chạy nguyên tử, tránh xung đột với leaveGroup đang xóa nhóm cùng lúc
        boolean[] added = {false};
        boolean exists = groups.computeIfPresent(group, (k, members) -> {
            added[0] = members.add(username);
            return members;
        }) != null;
        if (added[0]) {
            sendGroupUpdate(group);
        }
        return exists;
    }

    /** Rời nhóm; nhóm không còn ai thì bị xóa. Trả về false nếu không phải thành viên. */
    public boolean leaveGroup(String group, String username) {
        boolean[] removed = {false};
        Set<String> remaining = groups.computeIfPresent(group, (k, members) -> {
            removed[0] = members.remove(username);
            return members.isEmpty() ? null : members; // trả về null => xóa nhóm
        });
        if (!removed[0]) {
            return false;
        }
        if (remaining == null) {
            broadcastGroupList();
        } else {
            sendGroupUpdate(group);
        }
        return true;
    }

    public boolean isGroupMember(String group, String username) {
        Set<String> members = groups.get(group);
        return members != null && members.contains(username);
    }

    /** Phát tin nhắn tới tất cả thành viên trong nhóm (kể cả người gửi để đồng bộ hiển thị). */
    public void sendToGroup(String group, Packet packet) {
        Set<String> members = groups.get(group);
        if (members == null) {
            return;
        }
        for (String member : members) {
            ClientHandler h = onlineUsers.get(member);
            if (h != null) {
                h.send(packet);
            }
        }
    }

    public Packet groupListPacket() {
        return new Packet(ServerCommands.GROUP_LIST).put("groups", joinSorted(groups.keySet()));
    }

    private void broadcastGroupList() {
        broadcast(groupListPacket(), null);
    }

    private void sendGroupUpdate(String group) {
        Set<String> members = groups.get(group);
        if (members == null) {
            return;
        }
        sendToGroup(group, new Packet(ServerCommands.GROUP_UPDATE)
                .put("group", group)
                .put("members", joinSorted(members)));
    }

    // ================== Tiện ích ==================

    private static String joinSorted(Set<String> names) {
        List<String> list = new ArrayList<>(names);
        Collections.sort(list);
        return String.join(",", list);
    }

    static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    public static void main(String[] args) {
        ServerLogger.init("server.log");
        FileRelay relay = new FileRelay(Protocol.FILE_PORT);
        Thread relayThread = new Thread(relay::start, "file-relay");
        relayThread.setDaemon(true);
        relayThread.start();

        new ChatServer(Protocol.CHAT_PORT, relay).start();
    }
}
