package com.ycy.travelagent.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ycy.travelagent.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量 HMAC Token（JWT 的简化版）：
 * base64url(payload).base64url(HmacSHA256(payload))。
 * 不引入 jjwt 依赖，保持依赖面最小；生产可替换为正式 JWT 库。
 */
@Service
public class TokenService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(ObjectMapper objectMapper,
                        @Value("${app.security.token-secret}") String secret,
                        @Value("${app.security.token-ttl-hours:12}") long ttlHours) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlHours * 3600;
    }

    public String issue(UserAccount user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uid", user.id());
        payload.put("role", user.role());
        payload.put("exp", Instant.now().getEpochSecond() + ttlSeconds);
        try {
            String json = objectMapper.writeValueAsString(payload);
            String body = ENCODER.encodeToString(json.getBytes(StandardCharsets.UTF_8));
            return body + "." + sign(body);
        } catch (Exception e) {
            throw new IllegalStateException("签发 token 失败", e);
        }
    }

    /**
     * @return 校验通过的用户 ID
     * @throws ApiException 401：格式错误、签名不匹配或已过期
     */
    public String verifyAndGetUserId(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.unauthorized("未登录");
        }
        int dot = token.lastIndexOf('.');
        if (dot <= 0) {
            throw ApiException.unauthorized("token 格式错误");
        }
        String body = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.unauthorized("token 签名无效");
        }
        try {
            JsonNode node = objectMapper.readTree(DECODER.decode(body));
            long exp = node.path("exp").asLong(0);
            if (exp < Instant.now().getEpochSecond()) {
                throw ApiException.unauthorized("登录已过期，请重新登录");
            }
            String uid = node.path("uid").asText(null);
            if (uid == null) {
                throw ApiException.unauthorized("token 缺少用户信息");
            }
            return uid;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.unauthorized("token 解析失败");
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("token 签名失败", e);
        }
    }
}
