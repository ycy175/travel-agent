package com.ycy.travelagent.order;

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
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final UserRepository userRepository;

    public OrderController(OrderService orderService, UserRepository userRepository) {
        this.orderService = orderService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<TravelOrder> byApproval(HttpServletRequest request,
                                        @RequestParam String approvalId) {
        return orderService.listByApproval(approvalId, actor(request));
    }

    @GetMapping("/mine")
    public List<TravelOrder> mine(HttpServletRequest request) {
        return orderService.listMine(actor(request).id());
    }

    private UserAccount actor(HttpServletRequest request) {
        String userId = (String) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
    }
}
