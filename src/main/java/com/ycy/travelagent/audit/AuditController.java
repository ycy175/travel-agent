package com.ycy.travelagent.audit;

import com.ycy.travelagent.auth.AuthInterceptor;
import com.ycy.travelagent.auth.UserAccount;
import com.ycy.travelagent.auth.UserRepository;
import com.ycy.travelagent.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;
    private final UserRepository userRepository;

    public AuditController(AuditService auditService, UserRepository userRepository) {
        this.auditService = auditService;
        this.userRepository = userRepository;
    }

    /** 全量审计只对管理员开放。 */
    @GetMapping
    public List<AuditEntry> all(HttpServletRequest request,
                                @RequestParam(defaultValue = "100") int limit) {
        UserAccount actor = actor(request);
        if (!"ADMIN".equals(actor.role())) {
            throw ApiException.forbidden("只有管理员可以查看全量审计日志");
        }
        return auditService.recent(limit);
    }

    /** 普通员工只能看自己的操作记录。 */
    @GetMapping("/mine")
    public List<AuditEntry> mine(HttpServletRequest request,
                                 @RequestParam(defaultValue = "100") int limit) {
        return auditService.recentForUser(actor(request).id(), limit);
    }

    private UserAccount actor(HttpServletRequest request) {
        String userId = (String) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
    }
}
