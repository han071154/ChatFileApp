/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package common;

import java.io.Serializable;

/**
 * Model đại diện cho một client/user trong danh sách online.
 * Giữ nguyên tên file/class "client" theo cấu trúc project hiện tại.
 */
public class client implements Serializable {
    private static final long serialVersionUID = 1L;

    private String username;
    private boolean online;

    public client() {
    }

    public client(String username) {
        this(username, true);
    }

    public client(String username, boolean online) {
        this.username = username;
        this.online = online;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    @Override
    public String toString() {
        return username == null ? "" : username;
    }
}
