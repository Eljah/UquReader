package sun.misc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Minimal replacement for the legacy JDK {@code sun.misc.BASE64Encoder} that was
 * removed in newer Java versions. The Android Maven plugin still expects this
 * type to exist when it is executed on the build host, so we provide a simple
 * adapter that delegates to the supported {@link java.util.Base64} APIs.
 */
public class BASE64Encoder {
    private static final Charset OUTPUT_CHARSET = StandardCharsets.ISO_8859_1;
    private static final int LINE_LENGTH = 76;

    public BASE64Encoder() {
    }

    public String encode(byte[] buffer) {
        return Base64.getEncoder().encodeToString(buffer);
    }

    public void encode(byte[] buffer, OutputStream out) throws IOException {
        out.write(encode(buffer).getBytes(OUTPUT_CHARSET));
    }

    public void encode(InputStream in, OutputStream out) throws IOException {
        out.write(encode(readAll(in)).getBytes(OUTPUT_CHARSET));
    }

    public String encodeBuffer(byte[] buffer) {
        return chunkLines(encode(buffer));
    }

    public void encodeBuffer(byte[] buffer, OutputStream out) throws IOException {
        out.write(encodeBuffer(buffer).getBytes(OUTPUT_CHARSET));
    }

    public void encodeBuffer(InputStream in, OutputStream out) throws IOException {
        out.write(encodeBuffer(readAll(in)).getBytes(OUTPUT_CHARSET));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            data.write(buffer, 0, read);
        }
        return data.toByteArray();
    }

    private static String chunkLines(String encoded) {
        if (encoded.length() <= LINE_LENGTH) {
            return encoded + "\n";
        }
        StringBuilder builder = new StringBuilder(encoded.length() + encoded.length() / LINE_LENGTH + 1);
        int index = 0;
        while (index < encoded.length()) {
            int next = Math.min(encoded.length(), index + LINE_LENGTH);
            builder.append(encoded, index, next).append('\n');
            index = next;
        }
        return builder.toString();
    }
}
