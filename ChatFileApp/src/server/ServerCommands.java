package server;

/**
 * Các lệnh server dùng nhưng CHƯA có trong common/Protocol.java (do Kiều Anh quản lý).
 * Là giá trị trường "type" của Packet JSON. Khi Protocol.java được bổ sung chính thức
 * thì chuyển sang dùng hằng số bên đó và xóa file này.
 */
public final class ServerCommands {
    private ServerCommands() {
    }

    public static final String LOGOUT = "LOGOUT";
    public static final String ERROR = "ERROR";               // S->C {type, reason}
    public static final String USER_JOINED = "USER_JOINED";   // S->C {type, username}
    public static final String USER_LEFT = "USER_LEFT";       // S->C {type, username}

    // Chat nhóm
    public static final String GROUP_CREATE = "GROUP_CREATE"; // C->S {type, group}
    public static final String GROUP_JOIN = "GROUP_JOIN";     // C->S {type, group}
    public static final String GROUP_LEAVE = "GROUP_LEAVE";   // C->S {type, group}
    public static final String GROUP_MSG = "GROUP_MSG";       // C->S {type, group, content} / S->C thêm "from"
    public static final String GROUP_LIST = "GROUP_LIST";     // S->C {type, groups: "g1,g2"}
    public static final String GROUP_UPDATE = "GROUP_UPDATE"; // S->C {type, group, members: "u1,u2"}

    // Truyền file
    public static final String FILE_CANCEL = "FILE_CANCEL";   // {type, to, transferId}
    public static final String READY = "READY";               // FileRelay -> client, kèm "offset" cho SENDER
}
