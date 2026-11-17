package com.ycy.travelagent.auth;

/**
 * 员工账号。passwordHash 永不返回给前端。
 */
public record UserAccount(
        String id,
        String username,
        String passwordHash,
        String displayName,
        String employeeLevel,
        String role,
        String department,
        boolean enabled) {

    public boolean isApprover() {
        return "APPROVER".equals(role) || "ADMIN".equals(role);
    }
}
