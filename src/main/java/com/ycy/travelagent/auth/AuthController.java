package com.ycy.travelagent.auth;

import com.ycy.travelagent.audit.AuditService;
import com.ycy.travelagent.common.ApiException;
import com.ycy.travelagent.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final RateLimiter loginRateLimiter;

    public AuthController(UserRepository userRepository,
                          TokenService tokenService,
                          AuditService auditService,
                          @Qualifier("loginRateLimiter") RateLimiter loginRateLimiter) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request,
                                                     HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        if (!loginRateLimiter.tryAcquire("login:" + ip)) {
            throw new ApiException(429, "登录尝试过于频繁，请稍后再试");
        }
        if (request == null || request.username() == null || request.password() == null) {
            throw ApiException.badRequest("请输入用户名和密码");
        }
        String username = request.username().trim();
        UserAccount user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !PasswordHasher.matches(request.password(), user.passwordHash())) {
            auditService.record(null, null, AuditService.ACTION_LOGIN, username,
                    "username=" + username + ", result=bad-credentials", false, null, ip);
            throw ApiException.unauthorized("用户名或密码错误");
        }
        if (!user.enabled()) {
            auditService.record(user.id(), null, AuditService.ACTION_LOGIN, username,
                    "username=" + username + ", result=disabled", false, null, ip);
            throw ApiException.forbidden("账号已被禁用");
        }
        auditService.record(user.id(), null, AuditService.ACTION_LOGIN, username,
                "username=" + username + ", result=success", true, null, ip);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", tokenService.issue(user));
        body.put("expiresIn", tokenService.getTtlSeconds());
        body.put("user", view(user));
        return ResponseEntity.ok(body);
    }

    /** 供前端刷新页面时校验 token 是否仍有效。 */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        String userId = (String) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
        return ResponseEntity.ok(view(user));
    }

    private Map<String, Object> view(UserAccount user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", user.id());
        view.put("username", user.username());
        view.put("displayName", user.displayName());
        view.put("level", user.employeeLevel());
        view.put("role", user.role());
        view.put("department", user.department());
        return view;
    }
}
