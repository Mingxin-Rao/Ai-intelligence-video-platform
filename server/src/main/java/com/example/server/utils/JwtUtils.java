package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Minimal JWT (HS256) issuing & verification — no extra dependency.
 *
 * token layout: base64url(header).base64url(payload).base64url(HMAC-SHA256)
 * Signed with a server secret, so the frontend cannot forge a userId.
 */
@Component
public class JwtUtils {

    @Value("${auth.jwt.secret:}")
    private String secret;

    // Default validity: 7 days
    @Value("${auth.jwt.expire-ms:604800000}")
    private long expireMs;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /**
     * Issue a token for a user.
     */
    public String generate(long userId) {
        long now = System.currentTimeMillis();
        String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        JSONObject payload = new JSONObject();
        payload.put("uid", userId);
        payload.put("exp", now + expireMs);
        String body = B64.encodeToString(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + body;
        return signingInput + "." + sign(signingInput);
    }

    /**
     * Verify and extract the userId; returns null when invalid / expired / bad signature.
     */
    public Long parseUserId(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String signingInput = parts[0] + "." + parts[1];
            String expectedSig = sign(signingInput);
            // Constant-time signature compare
            if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            JSONObject payload = JSON.parseObject(new String(B64D.decode(parts[1]), StandardCharsets.UTF_8));
            long exp = payload.getLongValue("exp");
            if (exp > 0 && System.currentTimeMillis() > exp) return null; // expired
            return payload.getLong("uid");
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            String key = (secret == null || secret.isBlank()) ? "dovideo-dev-secret-change-me" : secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("JWT signing failed", e);
        }
    }
}
