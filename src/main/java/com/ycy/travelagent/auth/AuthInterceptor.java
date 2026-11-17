package com.ycy.travelagent.auth;

import com.ycy.travelagent.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 统一鉴权：/api/** 必须携带有效 token。
 * token 支持两种位置：
 * 1. Authorization: Bearer xxx          —— 普通 REST 请求
 * 2. ?token=xxx                         —— EventSource 无法自定义请求头，只能走查询参数
 */
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "auth.userId";
    public static final String ATTR_ROLE = "auth.role";
    public static final String ATTR_USERNAME = "auth.username";

    private final TokenService tokenService;
    private final UserRepository userRepository;

    public AuthInterceptor(TokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        try {
            String token = resolveToken(request);
            String userId = tokenService.verifyAndGetUserId(token);
            UserAccount user = userRepository.findById(userId)
                    .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
            if (!user.enabled()) {
                throw ApiException.forbidden("账号已被禁用");
            }
            request.setAttribute(ATTR_USER_ID, user.id());
            request.setAttribute(ATTR_ROLE, user.role());
            request.setAttribute(ATTR_USERNAME, user.username());
            return true;
        } catch (ApiException e) {
            writeJson(response, e.getStatus(), e.getMessage());
            return false;
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        return request.getParameter("token");
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + escape(message) + "\"}");
    }

    private String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
