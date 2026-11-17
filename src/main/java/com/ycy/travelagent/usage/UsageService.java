package com.ycy.travelagent.usage;

import com.ycy.travelagent.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 模型用量预算：按自然月统计用户所有会话的 token 消耗。
 * 超预算直接拒绝新对话，避免单个账号把企业额度打满。
 */
@Service
public class UsageService {

    public record UsageView(String month, long usedTokens, long limitTokens, long remaining) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final long monthlyLimit;

    public UsageService(JdbcTemplate jdbcTemplate,
                        @Value("${app.quota.monthly-tokens:200000}") long monthlyLimit) {
        this.jdbcTemplate = jdbcTemplate;
        this.monthlyLimit = monthlyLimit;
    }

    public long usedTokensThisMonth(String userId) {
        LocalDate firstDay = YearMonth.now().atDay(1);
        Long used = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(m.prompt_tokens, 0) + COALESCE(m.completion_tokens, 0)), 0)
                FROM chat_message m
                JOIN chat_session s ON s.id = m.session_id
                WHERE s.user_id = ? AND m.created_at >= ?
                """, Long.class, userId, Timestamp.valueOf(firstDay.atStartOfDay()));
        return used == null ? 0 : used;
    }

    public UsageView current(String userId) {
        long used = usedTokensThisMonth(userId);
        return new UsageView(YearMonth.now().toString(), used, monthlyLimit,
                Math.max(0, monthlyLimit - used));
    }

    public void ensureWithinQuota(String userId) {
        if (usedTokensThisMonth(userId) >= monthlyLimit) {
            throw new ApiException(429, "本月模型调用额度已用完（" + monthlyLimit + " tokens），请联系管理员");
        }
    }
}
