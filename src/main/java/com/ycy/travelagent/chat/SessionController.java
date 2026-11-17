package com.ycy.travelagent.chat;

import com.ycy.travelagent.auth.AuthInterceptor;
import com.ycy.travelagent.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话与历史的 REST 接口：前端不再依赖 localStorage 保存聊天记录。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatRepository chatRepository;

    public SessionController(ChatRepository chatRepository) {
        this.chatRepository = chatRepository;
    }

    @GetMapping
    public List<ChatRepository.Session> list(HttpServletRequest request) {
        return chatRepository.listSessions(userId(request));
    }

    @PostMapping
    public ResponseEntity<ChatRepository.Session> create(HttpServletRequest request,
                                                         @RequestBody(required = false) Map<String, String> body) {
        String userId = userId(request);
        String id = "sess_" + UUID.randomUUID().toString().replace("-", "");
        String title = body == null || body.get("title") == null || body.get("title").isBlank()
                ? "新的对话" : body.get("title").trim();
        chatRepository.createSession(id, userId, title);
        return ResponseEntity.ok(chatRepository.findSession(id).orElseThrow());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ChatRepository.Session> update(HttpServletRequest request,
                                                         @PathVariable String id,
                                                         @RequestBody Map<String, Object> body) {
        String userId = userId(request);
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        Boolean pinned = body.get("pinned") == null ? null : Boolean.valueOf(String.valueOf(body.get("pinned")));
        if ((title == null || title.isBlank()) && pinned == null) {
            throw ApiException.badRequest("没有需要更新的字段");
        }
        boolean ok = chatRepository.rename(id, userId, title, pinned);
        if (!ok) {
            throw ApiException.notFound("会话不存在");
        }
        return ResponseEntity.ok(chatRepository.findSession(id).orElseThrow());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String id) {
        if (!chatRepository.deleteSession(id, userId(request))) {
            throw ApiException.notFound("会话不存在");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/messages")
    public List<ChatRepository.Message> messages(HttpServletRequest request, @PathVariable String id) {
        String userId = userId(request);
        chatRepository.findOwned(id, userId).orElseThrow(() -> ApiException.notFound("会话不存在"));
        return chatRepository.listMessages(id);
    }

    static String userId(HttpServletRequest request) {
        return (String) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
