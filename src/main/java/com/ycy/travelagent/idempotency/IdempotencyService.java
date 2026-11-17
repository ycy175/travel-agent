package com.ycy.travelagent.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Slf4j
@Service
public class IdempotencyService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public String execute(String idempotencyKey, Supplier<String> action) {
        // 1. 尝试插入 INIT 状态（利用主键冲突实现原子锁）
        try {
            jdbcTemplate.update("INSERT INTO idempotency_record (idempotency_key, status) VALUES (?, ?)",
                    idempotencyKey, "INIT");
        } catch (DuplicateKeyException e) {
            // 2. 如果冲突，说明已经存在，查询当前状态
            String existingStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM idempotency_record WHERE idempotency_key = ?",
                    String.class, idempotencyKey);

            log.warn(">>> [幂等拦截] 键 {} 已存在，当前状态: {}", idempotencyKey, existingStatus);

            if ("SUBMITTING".equals(existingStatus) || "INIT".equals(existingStatus)) {
                return "系统正在处理该请求，请勿重复提交。";
            } else if ("SUCCESS".equals(existingStatus)) {
                // 如果已成功，直接返回上次的结果
                return jdbcTemplate.queryForObject(
                        "SELECT result_json FROM idempotency_record WHERE idempotency_key = ?",
                        String.class, idempotencyKey);
            }
        }

        // 3. 更新状态为 SUBMITTING，执行真实业务
        try {
            jdbcTemplate.update("UPDATE idempotency_record SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE idempotency_key = ?",
                    "SUBMITTING", idempotencyKey);

            String result = action.get(); // 执行实际的外部调用/入库操作

            // 4. 成功后，保存结果，更新为 SUCCESS
            jdbcTemplate.update("UPDATE idempotency_record SET status = ?, result_json = ?, updated_at = CURRENT_TIMESTAMP WHERE idempotency_key = ?",
                    "SUCCESS", result, idempotencyKey);
            return result;

        } catch (Exception e) {
            // 如果执行失败，删除记录，允许用户重试
            jdbcTemplate.update("DELETE FROM idempotency_record WHERE idempotency_key = ?", idempotencyKey);
            throw new RuntimeException("执行失败，请重试", e);
        }
    }
}