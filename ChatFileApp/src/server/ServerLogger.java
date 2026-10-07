package server;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Ghi log hệ thống ra màn hình console và file server.log (ghi nối tiếp).
 * Các luồng ClientHandler/FileRelay gọi đồng thời nên các hàm đều synchronized.
 */
public class ServerLogger {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // In ra console bằng UTF-8 để cửa sổ Output của NetBeans hiển thị đúng tiếng Việt
    private static final PrintStream CONSOLE = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    private static PrintWriter fileWriter;

    public static synchronized void init(String logFile) {
        try {
            fileWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            System.err.println("Không mở được file log " + logFile + ": " + e.getMessage());
        }
    }

    public static void info(String message) {
        log("INFO ", message);
    }

    public static void warn(String message) {
        log("WARN ", message);
    }

    public static void error(String message) {
        log("ERROR", message);
    }

    private static synchronized void log(String level, String message) {
        String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "] [" + level + "] [" + Thread.currentThread().getName() + "] " + message;
        CONSOLE.println(line);
        if (fileWriter != null) {
            fileWriter.println(line);
        }
    }
}
