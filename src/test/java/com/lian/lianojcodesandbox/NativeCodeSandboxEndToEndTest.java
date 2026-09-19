package com.lian.lianojcodesandbox;

import com.lian.lianojcodesandbox.model.ExecuteCodeRequest;
import com.lian.lianojcodesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author lian
 * @title NativeCodeSandboxEndToEndTest
 * @description 沙箱回归测试：锁定两条核心契约——
 * 1. 用例输入必须写入用户程序的标准输入（Scanner 代码能读到并算出正确结果）；
 * 2. 子进程输出必须按 UTF-8 读取（中文不乱码）。
 * 任何沙箱改动（模板方法、进程工具、Docker 实现切换）后都应跑本测试。
 */
class NativeCodeSandboxEndToEndTest {

    /** 标准 OJ 写法：Scanner 从标准输入读两个整数求和 */
    private static final String SCANNER_A_PLUS_B =
            "import java.util.Scanner;\n" +
            "public class Main {\n" +
            "    public static void main(String[] args) {\n" +
            "        Scanner sc = new Scanner(System.in);\n" +
            "        System.out.println(sc.nextInt() + sc.nextInt());\n" +
            "    }\n" +
            "}";

    /** 输出含中文的版本：验证子进程输出按 UTF-8 读取不乱码 */
    private static final String CHINESE_OUTPUT_A_PLUS_B =
            "import java.util.Scanner;\n" +
            "public class Main {\n" +
            "    public static void main(String[] args) {\n" +
            "        Scanner sc = new Scanner(System.in);\n" +
            "        int sum = sc.nextInt() + sc.nextInt();\n" +
            "        System.out.println(\"结果:\" + sum);\n" +
            "    }\n" +
            "}";

    @Test
    void scannerCodeReadsInputFromStdin() {
        JavaNativeCodeSandbox sandbox = new JavaNativeCodeSandbox();
        ExecuteCodeRequest request = new ExecuteCodeRequest();
        request.setCode(SCANNER_A_PLUS_B);
        request.setLanguage("java");
        request.setInputList(Arrays.asList("1 2", "5 6", "-3 8"));

        ExecuteCodeResponse response = sandbox.executeCode(request);

        List<String> expected = Arrays.asList("3", "11", "5");
        assertEquals(expected, response.getOutputList());
        assertEquals(1, response.getStatus());
    }

    @Test
    void chineseOutputIsReadAsUtf8() {
        JavaNativeCodeSandbox sandbox = new JavaNativeCodeSandbox();
        ExecuteCodeRequest request = new ExecuteCodeRequest();
        request.setCode(CHINESE_OUTPUT_A_PLUS_B);
        request.setLanguage("java");
        request.setInputList(Arrays.asList("7 8"));

        ExecuteCodeResponse response = sandbox.executeCode(request);

        assertEquals(Arrays.asList("结果:15"), response.getOutputList());
        assertEquals(1, response.getStatus());
    }
}
