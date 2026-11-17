package com.ycy.travelagent.policy;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 差标查询与合规校验。
 * 关键点：舱位等级用序号比较，酒店按"每晚均价"比较，规则全部来自数据库而不是 if-else。
 */
@Service
public class PolicyService {

    private final PolicyRepository policyRepository;

    public PolicyService(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    public Optional<Policy> resolve(String level, String city) {
        if (level == null || level.isBlank()) {
            return Optional.empty();
        }
        return policyRepository.find(level.trim(), normalizeCity(city));
    }

    public List<Policy> all() {
        return policyRepository.findAll();
    }

    /** 给 Agent 阅读的文本，包含命中规则来源，方便排查"为什么是这个标准"。 */
    public String describe(String level, String city) {
        String targetCity = normalizeCity(city);
        Optional<Policy> found = resolve(level, targetCity);
        if (found.isEmpty()) {
            return "未找到职级 " + level + " 在 " + targetCity + " 的差旅标准，请人工确认。";
        }
        Policy p = found.get();
        String scope = "*".equals(p.city()) ? "（该职级默认标准）" : "（城市专项标准）";
        return "职级 " + level + " 去往 " + targetCity + " 的差旅标准" + scope + "：\n"
                + "- 交通舱位：" + cabinName(p.flightClass()) + "\n"
                + "- 酒店上限：" + p.hotelLimit().stripTrailingZeros().toPlainString() + " 元/晚\n"
                + "- 差旅补贴：" + p.dailyAllowance().stripTrailingZeros().toPlainString() + " 元/天\n"
                + "- 需提前：" + p.advanceDays() + " 天申请\n"
                + "- 规则来源：" + p.id();
    }

    /**
     * 下单前校验。
     *
     * @return 为空表示合规；否则为人类可读的违规原因
     */
    public Optional<String> checkBooking(String level, String city, String type,
                                         BigDecimal amount, String cabinClass, int nights) {
        if (amount == null || amount.signum() <= 0) {
            return Optional.of("金额必须大于 0");
        }
        Optional<Policy> found = resolve(level, city);
        if (found.isEmpty()) {
            return Optional.of("未配置 " + level + " 的差旅标准，无法自动下单");
        }
        Policy policy = found.get();

        if ("HOTEL".equalsIgnoreCase(type)) {
            int stay = Math.max(nights, 1);
            BigDecimal perNight = amount.divide(BigDecimal.valueOf(stay), 2, RoundingMode.HALF_UP);
            if (perNight.compareTo(policy.hotelLimit()) > 0) {
                return Optional.of("酒店均价 " + perNight.toPlainString() + " 元/晚超过上限 "
                        + policy.hotelLimit().toPlainString() + " 元/晚");
            }
            return Optional.empty();
        }

        if ("FLIGHT".equalsIgnoreCase(type)) {
            String requested = (cabinClass == null || cabinClass.isBlank()) ? "ECONOMY" : cabinClass;
            int requestedRank = cabinRank(requested);
            int allowedRank = cabinRank(policy.flightClass());
            if (requestedRank < 0) {
                return Optional.of("未知舱位：" + requested);
            }
            if (requestedRank > allowedRank) {
                return Optional.of("舱位 " + cabinName(requested) + " 超过职级允许的 "
                        + cabinName(policy.flightClass()));
            }
            return Optional.empty();
        }

        return Optional.of("不支持的订单类型：" + type);
    }

    public String cabinName(String code) {
        if (code == null) {
            return "未知";
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "ECONOMY" -> "经济舱";
            case "PREMIUM" -> "超级经济舱";
            case "BUSINESS" -> "公务舱";
            case "FIRST" -> "头等舱";
            default -> code;
        };
    }

    private int cabinRank(String code) {
        if (code == null) {
            return -1;
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "ECONOMY" -> 0;
            case "PREMIUM" -> 1;
            case "BUSINESS" -> 2;
            case "FIRST" -> 3;
            default -> -1;
        };
    }

    private String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            return "*";
        }
        String value = city.trim();
        return value.endsWith("市") ? value.substring(0, value.length() - 1) : value;
    }
}
