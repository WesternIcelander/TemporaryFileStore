package io.siggi.temporaryfilestore;

import java.util.UUID;

public class FileInfo {
    public transient String fileId;
    public String fileName;
    public String contentType;
    public long expiry;
    public String uploaderIp;
    public UUID uploaderUuid;

    public FileInfo() {
    }

    public FileInfo(String fileId, String fileName, String contentType, long expiry, String uploaderIp, UUID uploaderUuid) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.expiry = expiry;
        this.uploaderIp = uploaderIp;
        this.uploaderUuid = uploaderUuid;
    }
}
