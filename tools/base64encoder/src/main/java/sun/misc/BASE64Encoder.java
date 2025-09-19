package sun.misc;

import java.util.Base64;

public class BASE64Encoder {
    public BASE64Encoder() {
    }

    public String encode(byte[] input) {
        return Base64.getEncoder().encodeToString(input);
    }
}
