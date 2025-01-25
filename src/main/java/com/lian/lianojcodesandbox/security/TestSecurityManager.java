package com.lian.lianojcodesandbox.security;

import cn.hutool.core.io.FileUtil;

import java.nio.charset.Charset;

/**
 * @author lian
 * @title TestSecurityManager
 * @date 2025/1/25 15:06
 * @description 测试安全管理器
 */
public class TestSecurityManager {
    public static void main(String[] args) {
        System.setSecurityManager(new MySecurityManager());
        FileUtil.writeString("aa", "aaa", Charset.defaultCharset());
    }
}
