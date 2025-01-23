package com.lian.lianojcodesandbox.unsafe;

/**
 * @author lian
 * @title SleepError
 * @date 2025/1/23 17:28
 * @description 无限睡眠（阻塞程序执行）
 */
public class SleepError {
    public static void main(String[] args) throws InterruptedException {
        long ONE_HOUR = 60 * 60 * 1000L;
        Thread.sleep(ONE_HOUR);
        System.out.println("睡完了");
    }
}
