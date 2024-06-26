package io.siggi.temporaryfilestore;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import io.siggi.http.io.MultipartFormDataParser;
import io.siggi.http.util.HTMLUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import static io.siggi.http.util.HTMLUtils.htmlentities;
import static io.siggi.http.util.Util.headerUrlEncode;
import static io.siggi.temporaryfilestore.Util.copy;
import static io.siggi.temporaryfilestore.Util.getExtension;
import static io.siggi.temporaryfilestore.Util.getServerLocation;
import static io.siggi.temporaryfilestore.Util.gson;
import static io.siggi.temporaryfilestore.Util.gsonPretty;
import static io.siggi.temporaryfilestore.Util.longToDateString;
import static io.siggi.temporaryfilestore.Util.randomDigits;
import static io.siggi.temporaryfilestore.Util.readStringFromFile;
import static io.siggi.temporaryfilestore.Util.writeStringToFile;

public class TemporaryFileStore {
    public static void main(String[] args) {
        int port = 8080;
        try {
            port = Integer.parseInt(System.getProperty("port"));
        } catch (Exception e) {
        }
        String root = System.getProperty("root", "store");
        if (root.endsWith("/") || root.endsWith("\\")) root = root.substring(0, root.length() - 1);
        try {
            new TemporaryFileStore(port, new File(root)).start();
        } catch (Exception e) {
        }
    }

    private final int port;
    private final File dataRoot;
    private final File storageRoot;
    private final File tmpDir;
    private final File resourcesDir;
    private final HTTPServer httpServer;
    private final String homepageFooter;
    private ServerSocket serverSocket;

    private boolean started = false;
    private boolean stopped = false;

    private final Map<String, UploadInfo> uploadInfos = new HashMap<>();

    private Thread listenerThread = null;
    private Thread cleanupThread = null;

    public TemporaryFileStore(int port, File dataRoot) {
        this.port = port;
        this.dataRoot = dataRoot;
        this.storageRoot = new File(dataRoot, "storage");
        this.tmpDir = new File(dataRoot, "tmp");
        this.resourcesDir = new File(dataRoot, "resources");
        if (!storageRoot.exists()) {
            storageRoot.mkdirs();
        }
        if (!tmpDir.exists()) {
            tmpDir.mkdirs();
        }
        String footer;
        try {
            footer = readStringFromFile(new File(dataRoot, "footer.txt"));
        } catch (Exception e) {
            footer = "Put footer text in " + (dataRoot.getAbsolutePath()) + "/footer.txt and restart the server";
        }
        this.homepageFooter = footer;
        httpServer = new HTTPServerBuilder().setTmpDir(tmpDir).build();
        httpServer.responderRegistry.register("/", this::respond, true, true);
        httpServer.setIgnoringMultipartFormData(true);
    }

    public void start() throws IOException {
        if (started) throw new IllegalStateException("Already started");
        serverSocket = new ServerSocket(port);
        (listenerThread = new Thread(() -> {
            try {
                while (true) {
                    Socket socket = serverSocket.accept();
                    httpServer.handle(socket);
                }
            } catch (Exception e) {
            }
        })).start();
        (cleanupThread = new Thread(() -> {
            while (true) {
                long now = System.currentTimeMillis();
                try {
                    File[] files = storageRoot.listFiles();
                    if (files != null)
                        for (File jsonFile : files) {
                            String name = jsonFile.getName();
                            if (!name.endsWith(".json")) continue;
                            File dataFile = new File(jsonFile.getParentFile(), name.substring(0, name.length() - 5));
                            try {
                                FileInfo fileInfo = gson.fromJson(readStringFromFile(jsonFile), FileInfo.class);
                                if (fileInfo.expiry < now) {
                                    dataFile.delete();
                                    jsonFile.delete();
                                }
                            } catch (Exception e) {
                            }
                        }
                } catch (Exception e) {
                }
                long expiredLastUpdate = now - 600000L;
                for (Iterator<Map.Entry<String, UploadInfo>> it = uploadInfos.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, UploadInfo> entry = it.next();
                    UploadInfo uploadInfo = entry.getValue();
                    if (uploadInfo.getLastUpdate() < expiredLastUpdate) {
                        it.remove();
                        if (!uploadInfo.isComplete()) {
                            new File(storageRoot, uploadInfo.getFileId()).delete();
                            new File(storageRoot, uploadInfo.getFileId() + ".json").delete();
                        }
                    }
                }
                cleanupAntiScrape();
                try {
                    Thread.sleep(3600000L);
                } catch (InterruptedException e) {
                    break;
                }
            }
        })).start();
        started = true;
    }

    public void stop() {
        if (stopped || !started) throw new IllegalStateException("Not currently running");
        try {
            serverSocket.close();
        } catch (Exception e) {
        }
        try {
            cleanupThread.interrupt();
        } catch (Exception e) {
        }
    }

    private void respond(HTTPRequest request) throws Exception {
        long now = System.currentTimeMillis();
        String serverLocation = getServerLocation(request);
        final UUID deviceUuid;
        final String deviceToken;
        {
            String token = request.cookies.get("token");
            UUID uuid = null;
            if (token != null) {
                uuid = Util.getUuid(token);
            }
            if (uuid == null) {
                token = Util.randomDigits(64);
                uuid = Util.getUuid(token);
            }
            deviceToken = token;
            deviceUuid = uuid;
        }
        Runnable setTokenCookie = () -> {
            long oneYearFromNow = now + (86400000L * 365L);
            request.response.setHeader("Set-Cookie", "token=" + deviceToken + "; path=/; expires=" + HTMLUtils.getSimpleDateFormat().format(new Date(oneYearFromNow)));
        };
        if (request.url.startsWith("/resources/")) {
            if (request.url.contains("..")) return;
            File overrideFile = new File(resourcesDir, request.url.substring(11));
            if (overrideFile.exists()) {
                request.response.returnFile(overrideFile);
                return;
            }
            returnJarResource(request, "/web" + request.url);
            return;
        }
        if (request.url.equals("/")) {
            setTokenCookie.run();
            String homepageString = Util.readJarStringResource("/web/uploader.html");
            homepageString = homepageString.replace("$footer", homepageFooter);
            homepageString = homepageString.replace("$deviceuuid", deviceUuid.toString());
            request.response.write(homepageString);
            return;
        }
        if (request.url.equals("/download")) {
            String fileId = request.get.getOrDefault("fileid", request.post.get("fileid"));
            if (fileId == null) {
                request.response.redirect("/");
                return;
            }
            if (fileId.startsWith(serverLocation + "/")) {
                fileId = fileId.substring(serverLocation.length() + 1);
            }
            request.response.redirect("/" + fileId);
            return;
        }
        if (request.url.equals("/preupload")) {
            try {
                long size = Long.parseLong(request.post.get("size"));
                UploadInfo uploadInfo = newUpload(deviceUuid, size);
                setTokenCookie.run();
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("token", deviceToken);
                result.addProperty("fileId", uploadInfo.getFileId());
                result.addProperty("link", serverLocation + "/" + uploadInfo.getFileId());
                request.response.setContentType("application/json");
                request.response.write(gson.toJson(result));
            } catch (Exception e) {
                JsonObject result = new JsonObject();
                result.addProperty("success", false);
                request.response.setContentType("application/json");
                request.response.write(gson.toJson(result));
            }
            return;
        }
        if (request.url.equals("/uploads")) {
            String timezone = request.post.getOrDefault("timezone", request.get.get("timezone"));
            JsonArray array = new JsonArray();
            List<FileInfo> allFiles = getAllFiles(file -> file.uploaderUuid.equals(deviceUuid));
            allFiles.sort(Comparator.comparing(a -> a.expiry));
            for (FileInfo fileInfo : allFiles) {
                JsonObject object = new JsonObject();
                array.add(object);
                object.addProperty("file", fileInfo.fileName);
                object.addProperty("fileId", fileInfo.fileId);
                object.addProperty("link", serverLocation + "/" + fileInfo.fileId);
                object.addProperty("expiry", longToDateString(fileInfo.expiry, timezone));
                object.addProperty("epochExpiry", fileInfo.expiry);
            }
            request.response.setContentType("application/json");
            request.response.write(gson.toJson(array));
            return;
        }
        if (request.url.equals("/delete")) {
            JsonObject result = new JsonObject();
            delete:
            try {
                String fileId = request.post.get("fileId");
                File dataFile = new File(storageRoot, fileId);
                File jsonFile = new File(storageRoot, fileId + ".json");
                if (!jsonFile.exists()) break delete;
                FileInfo fileInfo = readFileInfo(fileId);
                if (!fileInfo.uploaderUuid.equals(deviceUuid)) break delete;
                dataFile.delete();
                jsonFile.delete();
                UploadInfo uploadInfo = getUploadInfo(fileId);
                if (uploadInfo != null) {
                    uploadInfo.setCancelled(true);
                }
                result.addProperty("success", true);
            } catch (Exception e) {
            }
            if (!result.has("success")) result.addProperty("success", false);
            request.response.setContentType("application/json");
            request.response.write(gson.toJson(result));
            return;
        }
        if (request.url.equals("/upload")) {
            String uploadContentType = request.getHeader("Content-Type");
            if (uploadContentType == null) uploadContentType = "";
            if (!request.method.equals("POST") || !uploadContentType.startsWith("multipart/form-data")) {
                returnJarResource(request, "/web/404.html");
                return;
            }
            MultipartFormDataParser formDataParser = new MultipartFormDataParser(request.inStream, uploadContentType, httpServer.getHeaderSizeLimit());

            boolean jsonResponse = false;
            String timezone = null;
            String fileId = null;
            FileInfo fileInfo = null;
            MultipartFormDataParser.Part part;
            UploadInfo uploadInfo = null;
            while ((part = formDataParser.nextPart()) != null) {
                switch (part.getName()) {
                    case "json":
                        jsonResponse = true;
                        break;
                    case "timezone":
                        timezone = part.getValue();
                        break;
                    case "fileId":
                        fileId = part.getValue();
                        break;
                    case "file": {
                        if (fileId != null) {
                            uploadInfo = getUploadInfo(fileId);
                            if (!uploadInfo.getUploader().equals(deviceUuid) || uploadInfo.getAvailableData() > 0L) {
                                uploadInfo = null;
                                fileId = null;
                            }
                        }
                        if (fileId == null) {
                            uploadInfo = newUpload(deviceUuid, -1L);
                            fileId = uploadInfo.getFileId();
                        }
                        fileInfo = new FileInfo(uploadInfo.getFileId(), part.getFilename(), part.getContentType(), now + (60L * 60L * 24L * 2L * 1000L), request.getIPAddress(), deviceUuid);
                        File dataFile = new File(storageRoot, fileId);
                        File jsonFile = new File(storageRoot, fileId + ".json");
                        writeStringToFile(jsonFile, gsonPretty.toJson(fileInfo));
                        InputStream in = part.getInputStream();
                        try (FileOutputStream out = new FileOutputStream(dataFile)) {
                            byte[] buffer = new byte[4096];
                            long copied = 0L;
                            int c;
                            while ((c = in.read(buffer, 0, buffer.length)) >= 0) {
                                out.write(buffer, 0, c);
                                copied += c;
                                uploadInfo.setAvailableData(copied);
                            }
                        }
                        uploadInfo.setComplete(true);
                    }
                    break;
                }
            }
            String link = serverLocation + "/" + fileId;
            String expires = longToDateString(fileInfo.expiry, timezone);
            if (jsonResponse) {
                JsonObject object = new JsonObject();
                object.addProperty("file", fileInfo.fileName);
                object.addProperty("fileId", fileId);
                object.addProperty("link", link);
                object.addProperty("expiry", expires);
                object.addProperty("epochExpiry", fileInfo.expiry);
                request.response.setContentType("application/json");
                request.response.write(gson.toJson(object));
            } else {
                String uploadCompleteString = Util.readJarStringResource("/web/uploadcomplete.html")
                    .replace("$filename", htmlentities(fileInfo.fileName))
                    .replace("$link", link)
                    .replace("$expires", expires);
                request.response.write(uploadCompleteString);
            }
            return;
        }
        fileDownload:
        {
            String fileId = request.url.substring(1);
            boolean forceInline = false;
            if (fileId.endsWith(".i")) {
                fileId = fileId.substring(0, fileId.length() - 2);
                forceInline = true;
            }
            if (fileId.contains("/") || fileId.contains(".")) {
                break fileDownload;
            }
            if (getFailCount(request.getIPAddress()) >= 5) {
                recordFail(request.getIPAddress());
                request.response.setHeader("429 Too Many Requests");
                returnJarResource(request, "/web/429.html");
                return;
            }
            File dataFile = new File(storageRoot, fileId);
            File jsonFile = new File(storageRoot, fileId + ".json");
            if (!dataFile.exists() || !jsonFile.exists()) {
                recordFail(request.getIPAddress());
                break fileDownload;
            }
            FileInfo fileInfo = readFileInfo(fileId);
            if (fileInfo == null || fileInfo.expiry < now) {
                break fileDownload;
            }
            String displayType = "attachment";
            String contentType = httpServer.getMimeType(getExtension(fileInfo.fileName));
            if (fileInfo.contentType.startsWith("image/")) displayType = "inline";
            if (forceInline) {
                if (allowsForcedInline(fileInfo)) {
                    displayType = "inline";
                } else {
                    break fileDownload;
                }
            }
            request.response.setHeader("Content-Disposition", displayType + "; filename=\"" + headerUrlEncode(fileInfo.fileName) + "\"");
            returnFile(request, fileId, fileInfo, dataFile, contentType);
            return;
        }
        request.response.setHeader("404 Not Found");
        returnJarResource(request, "/web/404.html");
    }

    private boolean allowsForcedInline(FileInfo fileInfo) {
        if (fileInfo.contentType.startsWith("video/")) return true;
        return false;
    }

    private void returnFile(HTTPRequest request, String fileId, FileInfo fileInfo, File dataFile, String contentType) throws Exception {
        UploadInfo uploadInfo = getUploadInfo(fileId);
        if (uploadInfo == null || uploadInfo.isComplete()) {
            request.response.returnFile(dataFile, contentType);
            return;
        }
        boolean partialContent = false;
        long seekTo = -1L;
        long stopAt = -1L;
        long transferLimit = -1L;
        long fileSize = uploadInfo.getTotalSize();
        if (fileSize >= 0L) {
            String rangeHeader = request.getHeader("Range");
            if (rangeHeader != null) {
                try {
                    if (rangeHeader.startsWith("bytes=")) {
                        int dash = rangeHeader.indexOf("-");
                        String left = rangeHeader.substring(6, dash);
                        String right = rangeHeader.substring(dash + 1);
                        seekTo = Long.parseLong(left);
                        if (right.isEmpty()) {
                            stopAt = fileSize;
                        } else {
                            stopAt = Long.parseLong(right) + 1L;
                        }
                    }
                    if (seekTo >= 0L && stopAt > seekTo && stopAt <= fileSize && seekTo < uploadInfo.getAvailableData()) {
                        partialContent = true;
                        transferLimit = stopAt - seekTo;
                    } else {
                        seekTo = -1L;
                        stopAt = -1L;
                    }
                } catch (Exception ignored) {
                    seekTo = -1L;
                    stopAt = -1L;
                }
            }
            if (partialContent) {
                request.response.contentLength(stopAt - seekTo);
                request.response.setHeader("Content-Range", "bytes " + seekTo + "-" + (stopAt - 1) + "/" + fileSize);
                request.response.setHeader("206 Partial Content");
            } else {
                request.response.contentLength(fileSize);
            }
        }
        request.response.setContentType(contentType);
        try (RandomAccessFile raf = new RandomAccessFile(dataFile, "r")) {
            if (partialContent) {
                raf.seek(seekTo);
            }
            long amountTransferred = 0L;
            byte[] buffer = new byte[4096];
            while (amountTransferred < fileSize) {
                int amountRead = raf.read(buffer, 0, buffer.length);
                if (amountRead <= 0) {
                    uploadInfo.waitForData();
                    continue;
                }
                int amountToWrite = amountRead;
                if (transferLimit >= 0L) {
                    if ((long) amountToWrite > transferLimit) {
                        amountToWrite = (int) transferLimit;
                    }
                    transferLimit -= amountToWrite;
                }
                request.response.write(buffer, 0, amountToWrite);
                amountTransferred += amountToWrite;
                if (transferLimit == 0L) {
                    break;
                }
            }
        }
    }

    private UploadInfo newUpload(UUID uploader, long size) {
        synchronized (uploadInfos) {
            String fileId;
            int nextRandomDigits = 6;
            do {
                fileId = randomDigits(nextRandomDigits++);
            } while (!canUseFileId(fileId));
            UploadInfo info = new UploadInfo(fileId, uploader, size);
            uploadInfos.put(fileId, info);
            return info;
        }
    }

    private UploadInfo getUploadInfo(String fileId) {
        synchronized (uploadInfos) {
            return uploadInfos.get(fileId);
        }
    }

    private boolean canUseFileId(String fileId) {
        if (uploadInfos.containsKey(fileId)) return false;
        File fileData = new File(storageRoot, fileId);
        File fileJson = new File(storageRoot, fileId + ".json");
        return !fileData.exists() && !fileJson.exists();
    }

    private FileInfo readFileInfo(String fileId) {
        File jsonFile = new File(storageRoot, fileId + ".json");
        try {
            FileInfo fileInfo = gson.fromJson(readStringFromFile(jsonFile), FileInfo.class);
            fileInfo.fileId = fileId;
            return fileInfo;
        } catch (Exception e) {
            return null;
        }
    }

    private List<FileInfo> getAllFiles(Predicate<FileInfo> predicate) {
        List<FileInfo> files;
        File[] fileList = storageRoot.listFiles();
        if (fileList == null) {
            return new ArrayList<>();
        }
        files = new ArrayList<>(fileList.length / 2);
        for (File f : fileList) {
            String name = f.getName();
            if (!name.endsWith(".json")) continue;
            String fileId = name.substring(0, name.length() - 5);
            FileInfo fileInfo = readFileInfo(fileId);
            if (fileInfo != null && (predicate == null || predicate.test(fileInfo))) {
                files.add(fileInfo);
            }
        }
        return files;
    }

    private boolean returnJarResource(HTTPRequest request, String url) {
        try {
            InputStream resourceAsStream = TemporaryFileStore.class.getResourceAsStream(url);
            request.response.setContentType(httpServer.getMimeType(getExtension(url)));
            copy(resourceAsStream, request.response);
            return true;
        } catch (Exception e) {
        }
        return false;
    }

    private final Map<String, AntiScrape> antiScrapeMap = new HashMap<>();
    private final ReentrantReadWriteLock antiScrapeLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock antiScrapeRead = antiScrapeLock.readLock();
    private final ReentrantReadWriteLock.WriteLock antiScrapeWrite = antiScrapeLock.writeLock();

    private long getFailCount(String ip) {
        antiScrapeRead.lock();
        try {
            AntiScrape antiScrape = antiScrapeMap.get(ip);
            if (antiScrape == null) return 0;
            return antiScrape.getFailCount();
        } finally {
            antiScrapeRead.unlock();
        }
    }

    private void recordFail(String ip) {
        antiScrapeWrite.lock();
        try {
            AntiScrape antiScrape = antiScrapeMap.get(ip);
            if (antiScrape == null) antiScrapeMap.put(ip, antiScrape = new AntiScrape());
            antiScrape.recordFail();
        } finally {
            antiScrapeWrite.unlock();
        }
    }

    private void cleanupAntiScrape() {
        antiScrapeWrite.lock();
        try {
            antiScrapeMap.entrySet().removeIf(entry -> entry.getValue().getFailCount() == 0);
        } finally {
            antiScrapeWrite.unlock();
        }
    }
}
