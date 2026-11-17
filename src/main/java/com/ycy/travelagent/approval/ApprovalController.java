package com.ycy.travelagent.approval;

import com.ycy.travelagent.auth.AuthInterceptor;
import com.ycy.travelagent.auth.UserAccount;
import com.ycy.travelagent.auth.UserRepository;
import com.ycy.travelagent.chat.ChatRepository;
import com.ycy.travelagent.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final UserRepository userRepository;
    private final ChatRepository chatRepository;

    public ApprovalController(ApprovalService approvalService,
                              UserRepository userRepository,
                              ChatRepository chatRepository) {
        this.approvalService = approvalService;
        this.userRepository = userRepository;
        this.chatRepository = chatRepository;
    }

    @GetMapping
    public List<ApprovalService.ApprovalView> list(HttpServletRequest request,
                                                   @RequestParam String sessionId) {
        UserAccount actor = actor(request);
        chatRepository.findOwned(sessionId, actor.id())
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        return approvalService.listBySession(sessionId, actor).stream()
                .map(a -> approvalService.view(a, actor))
                .toList();
    }

    @GetMapping("/inbox")
    public List<ApprovalService.ApprovalView> inbox(HttpServletRequest request) {
        UserAccount actor = actor(request);
        return approvalService.inbox(actor).stream()
                .map(a -> approvalService.view(a, actor))
                .toList();
    }

    @GetMapping("/{id}")
    public ApprovalService.ApprovalView get(HttpServletRequest request, @PathVariable String id) {
        UserAccount actor = actor(request);
        return approvalService.view(approvalService.get(id, actor), actor);
    }

    @PostMapping("/{id}/approve")
    public ApprovalService.ApprovalView approve(HttpServletRequest request, @PathVariable String id) {
        UserAccount actor = actor(request);
        return approvalService.view(approvalService.approve(id, actor), actor);
    }

    @PostMapping("/{id}/reject")
    public ApprovalService.ApprovalView reject(HttpServletRequest request, @PathVariable String id,
                                               @RequestBody(required = false) Map<String, String> body) {
        UserAccount actor = actor(request);
        String reason = body == null ? null : body.get("reason");
        return approvalService.view(approvalService.reject(id, actor, reason), actor);
    }

    private UserAccount actor(HttpServletRequest request) {
        String userId = (String) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
    }
}
