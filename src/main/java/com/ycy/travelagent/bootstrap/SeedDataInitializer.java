package com.ycy.travelagent.bootstrap;

import com.ycy.travelagent.auth.UserRepository;
import com.ycy.travelagent.policy.Policy;
import com.ycy.travelagent.policy.PolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 演示数据初始化。用户名/政策为空时才写入，避免覆盖真实数据。
 */
@Component
public class SeedDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataInitializer.class);

    private final UserRepository userRepository;
    private final PolicyRepository policyRepository;

    public SeedDataInitializer(UserRepository userRepository, PolicyRepository policyRepository) {
        this.userRepository = userRepository;
        this.policyRepository = policyRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUsers();
        seedPolicies();
    }

    private void seedUsers() {
        if (userRepository.count() > 0) {
            return;
        }
        userRepository.insert("U1001", "zhangsan", "123456", "张伟", "P6", "EMPLOYEE", "研发部");
        userRepository.insert("U1002", "lisi", "123456", "李娜", "M3", "EMPLOYEE", "市场部");
        userRepository.insert("U2001", "wangwu", "123456", "王强", "M3", "APPROVER", "研发部");
        userRepository.insert("U9001", "admin", "admin123", "系统管理员", "M3", "ADMIN", "信息中心");
        log.info(">>> [Seed] 演示账号已初始化：zhangsan/123456、lisi/123456、wangwu/123456（审批人）、admin/admin123");
    }

    private void seedPolicies() {
        if (policyRepository.count() > 0) {
            return;
        }
        policyRepository.insert(new Policy("POL-P5-*", "P5", "*", "ECONOMY",
                new BigDecimal("500"), new BigDecimal("100"), 7));
        policyRepository.insert(new Policy("POL-P6-*", "P6", "*", "ECONOMY",
                new BigDecimal("500"), new BigDecimal("100"), 7));
        policyRepository.insert(new Policy("POL-P7-*", "P7", "*", "ECONOMY",
                new BigDecimal("600"), new BigDecimal("150"), 5));
        policyRepository.insert(new Policy("POL-M1-*", "M1", "*", "BUSINESS",
                new BigDecimal("800"), new BigDecimal("200"), 3));
        policyRepository.insert(new Policy("POL-M2-*", "M2", "*", "BUSINESS",
                new BigDecimal("800"), new BigDecimal("200"), 3));
        policyRepository.insert(new Policy("POL-M3-*", "M3", "*", "BUSINESS",
                new BigDecimal("800"), new BigDecimal("200"), 3));

        // 一线城市上浮，演示"城市专项优先于默认规则"
        policyRepository.insert(new Policy("POL-P6-SH", "P6", "上海", "ECONOMY",
                new BigDecimal("600"), new BigDecimal("120"), 7));
        policyRepository.insert(new Policy("POL-P6-BJ", "P6", "北京", "ECONOMY",
                new BigDecimal("650"), new BigDecimal("120"), 7));
        policyRepository.insert(new Policy("POL-M3-SH", "M3", "上海", "BUSINESS",
                new BigDecimal("900"), new BigDecimal("220"), 3));
        log.info(">>> [Seed] 差旅政策已初始化，共 9 条（含城市专项）");
    }
}
