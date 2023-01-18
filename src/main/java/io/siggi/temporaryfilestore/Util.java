package io.siggi.temporaryfilestore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.siggi.http.HTTPRequest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

public class Util {
    private Util() {
    }

    public static final Gson gson;
    public static final Gson gsonPretty;
    static {
        GsonBuilder builder = new GsonBuilder();

        gson = builder.create();
        builder.setPrettyPrinting();
        gsonPretty = builder.create();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        int count = 0;
        while ((count = in.read(buffer, 0, buffer.length)) != -1) {
            out.write(buffer, 0, count);
        }
    }

    public static String getExtension(String file) {
        int i = file.lastIndexOf(".");
        int j = file.lastIndexOf("/");
        if (i == -1 || i < j) {
            return "";
        }
        return file.substring(i + 1);
    }

    public static String readJarStringResource(String file) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream resourceAsStream = Util.class.getResourceAsStream(file);
            copy(resourceAsStream, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static String readStringFromFile(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static void writeStringToFile(File file, String data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final char[] digitCharset = "0123456789".toCharArray();

    public static String randomDigits(int length) {
        SecureRandom random = new SecureRandom();
        char[] digits = new char[length];
        for (int i = 0; i < length; i++) {
            digits[i] = digitCharset[random.nextInt(digitCharset.length)];
        }
        return new String(digits);
    }

    public static String getServerLocation(HTTPRequest request) {
        String protocol = request.getHeader("X-Forwarded-Proto");
        String host = request.getHeader("X-Forwarded-Host");
        if (protocol == null) protocol = "http";
        if (host == null) host = request.getHeader("Host");
        return protocol + "://" + host;
    }

    public static String longToDateString(long date, String timezone) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
        if (timezone != null) {
            try {
                sdf.setTimeZone(TimeZone.getTimeZone(timezone));
            } catch (Exception e) {
            }
        }
        return sdf.format(new Date(date));
    }

    private static final char[] hexCharset = "0123456789abcdef".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            hex[i * 2] = hexCharset[(((int) bytes[i]) >> 4) & 0xf];
            hex[(i * 2) + 1] = hexCharset[((int) bytes[i]) & 0xf];
        }
        return new String(hex);
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static UUID getUuid(String cookieString) {
        MessageDigest sha256 = sha256();
        sha256.update(cookieString.getBytes(StandardCharsets.UTF_8));
        byte[] hash = sha256.digest();
        String s = bytesToHex(hash).substring(0, 32).replaceAll("([0-9A-Fa-f]{8})([0-9A-Fa-f]{4})([0-9A-Fa-f]{4})([0-9A-Fa-f]{4})([0-9A-Fa-f]{12})", "$1-$2-$3-$4-$5");
        UUID uuid = UUID.fromString(s);
        long mostSignificant = uuid.getMostSignificantBits();
        long leastSignificant = uuid.getLeastSignificantBits();
        mostSignificant &= ~(0xFL << 12L);
        mostSignificant |= (0x4L << 12L);
        leastSignificant &= ~(0x3L << 62L);
        leastSignificant |= (0x1L << 63L);
        return new UUID(mostSignificant, leastSignificant);
    }
}
