package server;

import common.Protocol;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Client dòng lệnh để tự kiểm thử ChatServer khi chưa có giao diện Swing.
 * Chạy: Run File (Shift+F6). Tham số tùy chọn: địa chỉ IP server (mặc định localhost).
 *
 * Lệnh:
 *   /login <tên>                 /msg <user> <nội dung>
 *   /gcreate <nhóm>              /gjoin <nhóm>         /gleave <nhóm>
 *   /gmsg <nhóm> <nội dung>      /quit
 */
public class ServerTestClient {
    public static void main(String[] args) throws IOException {
        // In UTF-8 để cửa sổ Output của NetBeans hiển thị đúng tiếng Việt
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        String host = args.length > 0 ? args[0] : "localhost";
        try (Socket socket = new Socket(host, Protocol.CHAT_PORT)) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            System.out.println("Đã kết nối tới " + host + ":" + Protocol.CHAT_PORT + ". Gõ /login <tên> để bắt đầu.");

            // Luồng riêng in các gói tin server gửi về
            Thread reader = new Thread(() -> {
                try {
                    while (true) {
                        System.out.println("<< " + Packet.read(in));
                    }
                } catch (IOException e) {
                    System.out.println("Mất kết nối với server.");
                }
            }, "reader");
            reader.setDaemon(true);
            reader.start();

            Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
            while (scanner.hasNextLine()) {
                String[] parts = scanner.nextLine().trim().split("\\s+", 3);
                Packet packet = toPacket(parts);
                if (packet == null) {
                    System.out.println("Lệnh không hợp lệ.");
                    continue;
                }
                Packet.write(out, packet);
                if (ServerCommands.LOGOUT.equals(packet.getType())) {
                    break;
                }
            }
        }
    }

    private static Packet toPacket(String[] p) {
        switch (p[0]) {
            case "/login":
                return p.length >= 2 ? new Packet(Protocol.CMD_LOGIN).put("username", p[1]) : null;
            case "/msg":
                return p.length == 3 ? new Packet(Protocol.CMD_MSG).put("to", p[1]).put("content", p[2]) : null;
            case "/gcreate":
                return p.length >= 2 ? new Packet(ServerCommands.GROUP_CREATE).put("group", p[1]) : null;
            case "/gjoin":
                return p.length >= 2 ? new Packet(ServerCommands.GROUP_JOIN).put("group", p[1]) : null;
            case "/gleave":
                return p.length >= 2 ? new Packet(ServerCommands.GROUP_LEAVE).put("group", p[1]) : null;
            case "/gmsg":
                return p.length == 3 ? new Packet(ServerCommands.GROUP_MSG).put("group", p[1]).put("content", p[2]) : null;
            case "/quit":
                return new Packet(ServerCommands.LOGOUT);
            default:
                return null;
        }
    }
}
