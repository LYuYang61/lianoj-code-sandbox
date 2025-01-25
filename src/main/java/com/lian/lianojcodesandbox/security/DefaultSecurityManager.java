package com.lian.lianojcodesandbox.security;

import java.security.Permission;

/**
 * @author lian
 * @title DefaultSecurityManager
 * @date 2025/1/25 15:01
 * @description 默认安全管理器
 */
public class DefaultSecurityManager extends SecurityManager {

    // 检查所有的权限
    @Override
    public void checkPermission(Permission perm) {
        System.out.println("默认不做任何限制");
        System.out.println(perm);
    }
}
