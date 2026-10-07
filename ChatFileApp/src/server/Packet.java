package server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gói tin của ứng dụng: một đối tượng JSON phẳng (key/value đều là chuỗi).
 *
 * Định dạng khung truyền trên socket (Message Framing):
 *   [4 byte độ dài N (int, big-endian)] [N byte JSON mã hóa UTF-8]
 * Bên nhận đọc 4 byte độ dài rồi dùng readFully() đọc đủ N byte,
 * nên không bị vỡ/dính gói tin khi TCP phân mảnh.
 *
 * Ví dụ: {"type":"MSG","to":"han","content":"xin chao"}
 */
public class Packet {
    // Giới hạn kích thước 1 gói tin điều khiển (chống client gửi độ dài rác làm tràn bộ nhớ)
    public static final int MAX_PACKET_SIZE = 1024 * 1024;

    private final Map<String, String> fields = new LinkedHashMap<>();

    public Packet(String type) {
        fields.put("type", type);
    }

    private Packet() {
    }

    public String getType() {
        return fields.get("type");
    }

    public String get(String key) {
        return fields.get(key);
    }

    public long getLong(String key, long defaultValue) {
        try {
            return Long.parseLong(fields.get(key));
        } catch (NumberFormatException | NullPointerException e) {
            return defaultValue;
        }
    }

    public Packet put(String key, Object value) {
        fields.put(key, value == null ? null : String.valueOf(value));
        return this;
    }

    public Packet remove(String key) {
        fields.remove(key);
        return this;
    }

    // Tạo bản sao (dùng khi server chuyển tiếp gói tin và đổi "to" thành "from")
    public Packet copy() {
        Packet p = new Packet();
        p.fields.putAll(fields);
        return p;
    }

    // ================== Đọc / ghi khung trên socket ==================

    public static void write(DataOutputStream out, Packet packet) throws IOException {
        byte[] data = packet.toJson().getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    public static Packet read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > MAX_PACKET_SIZE) {
            throw new IOException("Độ dài gói tin không hợp lệ: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return fromJson(new String(data, StandardCharsets.UTF_8));
    }

    // ================== JSON ==================

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendString(sb, e.getKey());
            sb.append(':');
            if (e.getValue() == null) {
                sb.append("null");
            } else {
                appendString(sb, e.getValue());
            }
        }
        return sb.append('}').toString();
    }

    @Override
    public String toString() {
        return toJson();
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // Parser JSON tối giản cho đối tượng phẳng {"k":"v",...}; giá trị số/true/false/null được đọc thành chuỗi
    public static Packet fromJson(String json) throws IOException {
        Parser p = new Parser(json);
        Packet packet = new Packet();
        p.skipWs();
        p.expect('{');
        p.skipWs();
        if (p.peek() != '}') {
            while (true) {
                p.skipWs();
                String key = p.readString();
                p.skipWs();
                p.expect(':');
                p.skipWs();
                packet.fields.put(key, p.readValue());
                p.skipWs();
                char c = p.next();
                if (c == '}') break;
                if (c != ',') throw new IOException("JSON lỗi: thiếu ',' tại vị trí " + p.pos);
            }
        } else {
            p.next();
        }
        if (packet.getType() == null) {
            throw new IOException("Gói tin thiếu trường \"type\"");
        }
        return packet;
    }

    private static class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        char peek() throws IOException {
            if (pos >= s.length()) throw new IOException("JSON lỗi: kết thúc đột ngột");
            return s.charAt(pos);
        }

        char next() throws IOException {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) throws IOException {
            if (next() != c) throw new IOException("JSON lỗi: cần '" + c + "' tại vị trí " + (pos - 1));
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        String readValue() throws IOException {
            if (peek() == '"') return readString();
            int start = pos;
            while (pos < s.length() && ",}".indexOf(s.charAt(pos)) < 0 && !Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            String raw = s.substring(start, pos);
            if (raw.isEmpty()) throw new IOException("JSON lỗi: thiếu giá trị tại vị trí " + start);
            return raw.equals("null") ? null : raw;
        }

        String readString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw new IOException("JSON lỗi: \\u thiếu ký tự");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new IOException("JSON lỗi: \\u không hợp lệ");
                        }
                        pos += 4;
                        break;
                    default:
                        throw new IOException("JSON lỗi: escape không hợp lệ \\" + e);
                }
            }
        }
    }
}
