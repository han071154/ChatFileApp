package server;

import common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Kênh truyền file (cổng 5001): ghép cặp người gửi - người nhận theo transferId và chuyển tiếp dữ liệu.
 *
 * Quy trình:
 *  1. Trên kênh chat, người nhận gửi FILE_RESP ACCEPT -> ClientHandler gọi authorize(transferId, ...).
 *  2. Hai bên mở kết nối tới cổng 5001 và gửi gói tin (Packet có 4 byte độ dài):
 *       SENDER:   {"type":"FILE_INIT","transferId":"...","role":"SENDER"}
 *       RECEIVER: {"type":"FILE_INIT","transferId":"...","role":"RECEIVER","offset":"<số byte đã có>"}
 *     "offset" dùng cho truyền tiếp (resume); bỏ trống hoặc 0 nếu nhận từ đầu.
 *  3. Khi đủ 2 bên, relay gửi {"type":"READY","offset":"..."} cho cả hai.
 *     SENDER nhảy tới vị trí offset trong file rồi gửi phần còn lại dưới dạng byte thô.
 *  4. Relay chép byte từ SENDER sang RECEIVER cho tới khi SENDER đóng kết nối.
 *  5. Hủy: một bên đóng socket, hoặc gửi FILE_CANCEL trên kênh chat -> relay đóng cả hai socket.
 *
 * Bên chờ ghép cặp KHÔNG giữ luồng: socket được cất vào bảng chờ và luồng trả về Thread Pool.
 */
public class FileRelay {
    public static final int MAX_CONCURRENT_TRANSFERS = 10;
    private static final int HANDSHAKE_TIMEOUT_MS = 15_000;  // thời gian chờ gói FILE_INIT
    private static final int TRANSFER_IDLE_TIMEOUT_MS = 60_000; // người gửi im lặng quá lâu thì hủy
    private static final long PAIRING_TIMEOUT_SECONDS = 120;   // phiên đã ACCEPT mà không ai kết nối

    private final int port;
    // transferId -> phiên truyền đã được người nhận chấp nhận
    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService cleaner;

    public FileRelay(int port) {
        this.port = port;
        // Mỗi phiên dùng tối đa 2 luồng trong lúc bắt tay, 1 luồng trong lúc chép dữ liệu
        this.pool = new ThreadPoolExecutor(0, MAX_CONCURRENT_TRANSFERS * 2, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), ChatServer.namedThreadFactory("file"));
        this.cleaner = Executors.newSingleThreadScheduledExecutor(ChatServer.namedThreadFactory("file-cleaner"));
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            ServerLogger.info("FileRelay đang lắng nghe tại cổng " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                try {
                    pool.execute(() -> handleConnection(socket));
                } catch (RejectedExecutionException e) {
                    ServerLogger.warn("FileRelay quá tải, từ chối kết nối từ " + socket.getRemoteSocketAddress());
                    closeQuietly(socket);
                }
            }
        } catch (IOException e) {
            ServerLogger.error("FileRelay dừng do lỗi: " + e.getMessage());
        }
    }

    // ================== Gọi từ ClientHandler (kênh chat) ==================

    /** Cho phép phiên truyền transferId (gọi khi người nhận ACCEPT). Gọi lại với cùng id để truyền tiếp. */
    public void authorize(String transferId, String sender, InetAddress senderAddress,
                          String receiver, InetAddress receiverAddress) {
        Transfer transfer = new Transfer(transferId, sender, senderAddress, receiver, receiverAddress);
        Transfer old = transfers.put(transferId, transfer);
        if (old != null) {
            old.close(); // phiên cũ cùng id (vd: đang truyền dở) bị thay bằng phiên mới
        }
        cleaner.schedule(() -> {
            if (!transfer.isStarted() && transfers.remove(transferId, transfer)) {
                ServerLogger.warn("[" + transferId + "] Hết thời gian chờ ghép cặp, hủy phiên");
                transfer.close();
            }
        }, PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Hủy phiên truyền; chỉ người gửi hoặc người nhận của phiên đó mới hủy được. */
    public void cancel(String transferId, String requestedBy) {
        Transfer transfer = transfers.get(transferId);
        if (transfer != null && transfer.involves(requestedBy) && transfers.remove(transferId, transfer)) {
            transfer.cancelled = true;
            transfer.close();
        }
    }

    // ================== Xử lý kết nối cổng 5001 ==================

    private void handleConnection(Socket socket) {
        String address = String.valueOf(socket.getRemoteSocketAddress());
        Transfer transfer;
        DataOutputStream out;
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            Packet init = Packet.read(in);

            String transferId = init.get("transferId");
            String role = init.get("role");
            transfer = transferId == null ? null : transfers.get(transferId);

            String reason = null;
            if (!Protocol.CMD_FILE_INIT.equals(init.getType())) {
                reason = "Cần gói FILE_INIT";
            } else if (transfer == null) {
                reason = "Phiên truyền không tồn tại hoặc chưa được chấp nhận";
            } else if (!Protocol.ROLE_SENDER.equals(role) && !Protocol.ROLE_RECEIVER.equals(role)) {
                reason = "role phải là SENDER hoặc RECEIVER";
            } else if (!transfer.addressMatches(role, socket.getInetAddress())) {
                reason = "Địa chỉ IP không khớp với người dùng của phiên";
            }
            if (reason != null) {
                ServerLogger.warn("FileRelay từ chối " + address + ": " + reason);
                Packet.write(out, new Packet(ServerCommands.ERROR).put("reason", reason));
                closeQuietly(socket);
                return;
            }

            Endpoint endpoint = new Endpoint(socket, in, out);
            boolean bothConnected;
            synchronized (transfer) {
                if (transfer.isClosed()) {
                    reason = "Phiên truyền đã bị hủy";
                } else if (Protocol.ROLE_SENDER.equals(role)) {
                    if (transfer.senderSide != null) reason = "Đã có SENDER cho phiên này";
                    else transfer.senderSide = endpoint;
                } else {
                    if (transfer.receiverSide != null) reason = "Đã có RECEIVER cho phiên này";
                    else {
                        transfer.receiverSide = endpoint;
                        transfer.offset = Math.max(0, init.getLong("offset", 0));
                    }
                }
                bothConnected = reason == null && transfer.senderSide != null && transfer.receiverSide != null;
                if (bothConnected) {
                    transfer.started = true;
                }
            }
            if (reason != null) {
                ServerLogger.warn("FileRelay từ chối " + address + ": " + reason);
                Packet.write(out, new Packet(ServerCommands.ERROR).put("reason", reason));
                closeQuietly(socket);
                return;
            }

            ServerLogger.info("[" + transferId + "] " + role + " đã kết nối từ " + address);
            if (!bothConnected) {
                return; // chờ bên còn lại; luồng này trả về pool, socket vẫn mở
            }
        } catch (IOException e) {
            ServerLogger.warn("FileRelay: lỗi bắt tay với " + address + ": " + e.getMessage());
            closeQuietly(socket);
            return;
        }

        relay(transfer);
    }

    /** Chép dữ liệu SENDER -> RECEIVER. Chạy trên luồng của kết nối tới sau cùng. */
    private void relay(Transfer t) {
        long startTime = System.currentTimeMillis();
        long bytes = 0;
        String result;
        try {
            Packet ready = new Packet(ServerCommands.READY).put("transferId", t.id).put("offset", t.offset);
            Packet.write(t.receiverSide.out, ready);
            Packet.write(t.senderSide.out, ready);
            ServerLogger.info("[" + t.id + "] Bắt đầu chuyển tiếp '" + t.sender + "' -> '" + t.receiver
                    + "'" + (t.offset > 0 ? " (truyền tiếp từ byte " + t.offset + ")" : ""));

            t.senderSide.socket.setSoTimeout(TRANSFER_IDLE_TIMEOUT_MS);
            t.receiverSide.socket.setSoTimeout(0);
            // Dùng lại stream đã bọc buffer để không mất byte đã đọc trước vào buffer
            InputStream from = t.senderSide.in;
            OutputStream to = t.receiverSide.out;
            byte[] buffer = new byte[Protocol.BUFFER_SIZE];
            int n;
            while ((n = from.read(buffer)) != -1) {
                to.write(buffer, 0, n);
                bytes += n;
            }
            to.flush();
            t.receiverSide.socket.shutdownOutput(); // báo EOF cho người nhận
            result = "Hoàn tất";
        } catch (SocketTimeoutException e) {
            result = "Người gửi không phản hồi quá " + TRANSFER_IDLE_TIMEOUT_MS / 1000 + " giây";
        } catch (IOException e) {
            result = t.cancelled ? "Đã bị hủy" : "Mất kết nối (" + e.getMessage() + ")";
        } finally {
            transfers.remove(t.id, t);
            t.close();
        }

        long ms = Math.max(1, System.currentTimeMillis() - startTime);
        ServerLogger.info(String.format("[%s] %s: %,d bytes trong %.1f s (%.2f MB/s)",
                t.id, result, bytes, ms / 1000.0, bytes / 1024.0 / 1024.0 / (ms / 1000.0)));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ================== Dữ liệu phiên truyền ==================

    private static class Endpoint {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Endpoint(Socket socket, DataInputStream in, DataOutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }
    }

    private static class Transfer {
        final String id;
        final String sender;
        final String receiver;
        final InetAddress senderAddress;
        final InetAddress receiverAddress;
        Endpoint senderSide;
        Endpoint receiverSide;
        long offset;
        volatile boolean started;
        volatile boolean cancelled;
        private volatile boolean closed;

        Transfer(String id, String sender, InetAddress senderAddress, String receiver, InetAddress receiverAddress) {
            this.id = id;
            this.sender = sender;
            this.senderAddress = senderAddress;
            this.receiver = receiver;
            this.receiverAddress = receiverAddress;
        }

        boolean involves(String user) {
            return sender.equals(user) || receiver.equals(user);
        }

        // Kết nối 5001 phải đến từ cùng địa chỉ IP với kết nối chat của user đó
        boolean addressMatches(String role, InetAddress address) {
            InetAddress expected = Protocol.ROLE_SENDER.equals(role) ? senderAddress : receiverAddress;
            return expected == null || expected.equals(address);
        }

        boolean isStarted() {
            return started;
        }

        boolean isClosed() {
            return closed;
        }

        synchronized void close() {
            closed = true;
            if (senderSide != null) closeQuietly(senderSide.socket);
            if (receiverSide != null) closeQuietly(receiverSide.socket);
        }
    }
}
