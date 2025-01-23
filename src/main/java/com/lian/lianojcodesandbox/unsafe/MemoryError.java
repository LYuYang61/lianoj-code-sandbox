package com.lian.lianojcodesandbox.unsafe;

import java.util.ArrayList;
import java.util.List;

/**
 * @author lian
 * @title MemoryError
 * @date 2025/1/23 17:28
 * @description 内存溢出
 */
public class MemoryError {
    public static void main(String[] args) throws InterruptedException {
        List<byte[]> bytes = new ArrayList<>();
        while (true) {
            bytes.add(new byte[10000]);
        }
    }
}
