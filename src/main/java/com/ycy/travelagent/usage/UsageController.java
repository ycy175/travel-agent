package com.ycy.travelagent.usage;

import com.ycy.travelagent.auth.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public UsageService.UsageView current(HttpServletRequest request) {
        String userId = (String) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return usageService.current(userId);
    }
}
