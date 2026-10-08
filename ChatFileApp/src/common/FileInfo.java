/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package common;

import java.io.Serializable;

/**
 * Thông tin chung của một phiên truyền file.
 * Không chứa dữ liệu byte của file; chỉ chứa metadata để Client/Server
 * thương lượng phiên truyền và cập nhật tiến trình.
 */
public class FileInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String transferId;
    private String sender;
    private String receiver;
    private String fileName;
    private long fileSize;
    private long offset;

    public FileInfo() {
    }

    public FileInfo(String transferId, String sender, String receiver,
                    String fileName, long fileSize) {
        this(transferId, sender, receiver, fileName, fileSize, 0L);
    }

    public FileInfo(String transferId, String sender, String receiver,
                    String fileName, long fileSize, long offset) {
        this.transferId = transferId;
        this.sender = sender;
        this.receiver = receiver;
        this.fileName = fileName;
        this.fileSize = Math.max(0L, fileSize);
        this.offset = Math.max(0L, offset);
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = Math.max(0L, fileSize);
    }

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = Math.max(0L, offset);
    }

    /** Phần trăm hoàn thành dựa trên offset hiện tại. */
    public int getProgressPercent() {
        if (fileSize <= 0) {
            return 0;
        }
        return (int) Math.min(100L, (offset * 100L) / fileSize);
    }

    @Override
    public String toString() {
        return "FileInfo{" +
                "transferId='" + transferId + '\'' +
                ", sender='" + sender + '\'' +
                ", receiver='" + receiver + '\'' +
                ", fileName='" + fileName + '\'' +
                ", fileSize=" + fileSize +
                ", offset=" + offset +
                '}';
    }
}
