package com.lian.lianojcodesandbox.security;

import java.security.Permission;

/**
 * @author lian
 * @title DenySecurityManager
 * @date 2025/1/25 15:02
 * @description 禁用所有权限的安全管理器
 */
public class DenySecurityManager extends SecurityManager {

    // 检查所有的权限
    @Override
    public void checkPermission(Permission perm) {
        throw new SecurityException("权限异常：" + perm.toString());
    }
}
