package dev.dineshrama.webhook;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class Signatures {
    private final LabProperties config;
    public Signatures(LabProperties config) { this.config=config; }
    public String sign(byte[] body, long timestamp) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
            return "v1="+HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public void verify(byte[] body, String timestamp, String signature) {
        try {
            long seconds=Long.parseLong(timestamp);
            long now=Instant.now().getEpochSecond();
            if (seconds < now-300 || seconds > now+300 || signature==null || !constantEquals(sign(body, seconds),signature))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ApiProblem(401,"INVALID_SIGNATURE","A valid signature and a timestamp within five minutes are required.");
        }
    }
    static boolean constantEquals(String expected, String supplied) {
        return supplied!=null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
