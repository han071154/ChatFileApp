/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package common;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Dữ liệu dùng chung cho một tin nhắn chat riêng.
 *
 * Lớp này là model dùng chung giữa Client và Server. Giao thức thực tế
 * vẫn truyền bằng Packet JSON; Message giúp phần giao diện dễ quản lý
 * lịch sử tin nhắn mà không phải thao tác với chuỗi rời rạc.
 */
public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    private String from;
    private String to;
    private String content;
    private LocalDateTime timestamp;

    public Message() {
        this.timestamp = LocalDateTime.now();
    }

    public Message(String from, String to, String content) {
        this(from, to, content, LocalDateTime.now());
    }

    public Message(String from, String to, String content, LocalDateTime timestamp) {
        this.from = from;
        this.to = to;
        this.content = content;
        this.timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "Message{" +
                "from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", content='" + content + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
