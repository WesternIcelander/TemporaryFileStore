package io.siggi.temporaryfilestore;

public class AntiScrape {
    private int failCount = 0;
    private long lastActivity = 0L;

    public AntiScrape() {
    }

    public void recordFail() {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (now - lastActivity > 120000L) {
                failCount = 0;
            }
            lastActivity = now;
            failCount += 1;
        }
    }

    public int getFailCount() {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (now - lastActivity > 120000L) {
                return 0;
            }
            return failCount;
        }
    }
}
