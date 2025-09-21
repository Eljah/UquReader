package sun.misc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Companion to {@link BASE64Encoder} that mimics the legacy decoder API relied on
 * by the Android Maven plugin when running on newer JDKs.
 */
public class BASE64Decoder {
    public BASE64Decoder() {
    }

    public byte[] decodeBuffer(String input) throws IOException {
        try {
            return Base64.getMimeDecoder().decode(input);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid Base64 input", ex);
        }
    }

    public byte[] decodeBuffer(InputStream in) throws IOException {
        return decodeBuffer(new String(readAll(in), StandardCharsets.ISO_8859_1));
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
}
