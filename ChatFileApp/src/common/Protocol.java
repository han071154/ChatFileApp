/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package common;

public class Protocol {
    // Port quy ước
    public static final int CHAT_PORT = 5000;
    public static final int FILE_PORT = 5001;

    // Phân tách lệnh
    public static final String DELIMITER = "\\|";
    public static final String SEP = "|";

    // Kích thước buffer truyền file (64KB để tối ưu throughput file lớn)
    public static final int BUFFER_SIZE = 64 * 1024;

    // Các lệnh điều khiển chat
    public static final String CMD_LOGIN = "LOGIN";
    public static final String CMD_LOGIN_OK = "LOGIN_SUCCESS";
    public static final String CMD_LOGIN_FAIL = "LOGIN_FAILED";
    public static final String CMD_USER_LIST = "USER_LIST";
    public static final String CMD_MSG = "MSG";

    // Các lệnh thương lượng file (qua port Chat)
    public static final String CMD_FILE_REQ = "FILE_REQ";       // FILE_REQ|toUser|transferId|fileName|fileSize
    public static final String CMD_FILE_RESP = "FILE_RESP";     // FILE_RESP|toUser|transferId|ACCEPT/REJECT
    public static final String STATUS_ACCEPT = "ACCEPT";
    public static final String STATUS_REJECT = "REJECT";

    // Header bắt tay trên port truyền file riêng (port 5001)
    public static final String CMD_FILE_INIT = "FILE_INIT";     // FILE_INIT|transferId|role (SENDER/RECEIVER)
    public static final String ROLE_SENDER = "SENDER";
    public static final String ROLE_RECEIVER = "RECEIVER";

    // Helper tạo lệnh nhanh
    public static String createMsg(String toUser, String content) {
        return CMD_MSG + SEP + toUser + SEP + content;
    }

    public static String createFileReq(String toUser, String transferId, String fileName, long fileSize) {
        return CMD_FILE_REQ + SEP + toUser + SEP + transferId + SEP + fileName + SEP + fileSize;
    }

    public static String createFileResp(String toUser, String transferId, boolean accept) {
        return CMD_FILE_RESP + SEP + toUser + SEP + transferId + SEP + (accept ? STATUS_ACCEPT : STATUS_REJECT);
    }
}