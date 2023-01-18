package io.siggi.temporaryfilestore;

import java.io.IOException;
import java.util.UUID;

public class UploadInfo {
    private final String fileId;
    private final UUID uploader;
    private boolean complete = false;
    private boolean cancelled = false;
    private long availableData;
    private final long totalSize;
    private long lastUpdate;
    private final Object lock = new Object();

    public UploadInfo(String fileId, UUID uploader, long totalSize) {
        this.fileId = fileId;
        this.uploader = uploader;
        this.totalSize = totalSize;
        this.lastUpdate = System.currentTimeMillis();
    }

    public void waitForData() throws InterruptedException, IOException {
        synchronized (lock) {
            long recentUpdate = lastUpdate;
            long lastAvailable = availableData;
            while (!complete && recentUpdate == lastUpdate && lastAvailable == availableData) {
                if (cancelled) {
                    throw new IOException("Upload was cancelled");
                }
                if (System.currentTimeMillis() - lastUpdate > 15000L) {
                    throw new IOException("Timed out waiting for data");
                }
                lock.wait(5000L);
            }
        }
    }

    public void setAvailableData(long availableData) {
        synchronized (lock) {
            this.lastUpdate = System.currentTimeMillis();
            this.availableData = availableData;
            lock.notifyAll();
        }
    }

    public boolean isComplete() {
        synchronized (lock) {
            return complete;
        }
    }

    public void setComplete(boolean complete) {
        synchronized (lock) {
            this.lastUpdate = System.currentTimeMillis();
            this.complete = complete;
            lock.notifyAll();
        }
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    public void setCancelled(boolean cancelled) {
        synchronized (lock) {
            this.lastUpdate = System.currentTimeMillis();
            this.cancelled = cancelled;
            lock.notifyAll();
        }
    }

    public long getLastUpdate() {
        synchronized (lock) {
            return lastUpdate;
        }
    }

    public String getFileId() {
        return fileId;
    }

    public UUID getUploader() {
        return uploader;
    }

    public long getAvailableData() {
        synchronized (lock) {
            return availableData;
        }
    }

    public long getTotalSize() {
        return totalSize;
    }
}
