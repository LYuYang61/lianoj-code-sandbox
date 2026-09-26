package com.lian.lianojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import com.lian.lianojcodesandbox.language.LanguageConfig;
import com.lian.lianojcodesandbox.language.LanguageConfigRegistry;
import com.lian.lianojcodesandbox.model.ExecuteCodeRequest;
import com.lian.lianojcodesandbox.model.ExecuteCodeResponse;
import com.lian.lianojcodesandbox.model.ExecuteMessage;
import com.lian.lianojcodesandbox.utils.ProcessUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author lian
 * @title JavaNativeCodeSandbox
 * @date 2025/2/2 15:40
 * @description Java 原生代码沙箱实现：在本机用子进程编译并运行用户代码
 *
 * <p>适用场景：本地开发/联调（快、零额外依赖）。编译发生在宿主机（享受 E11 编译缓存），
 * 因此要求宿主机装有对应语言的编译器/解释器（java 天然满足；c++ 需 g++；python 需 python）。
 * 安全防护层级见学习文档 04 篇；需要更强隔离与内存统计时切换 {@link JavaDockerCodeSandbox}。
 */
@Component
public class JavaNativeCodeSandbox extends JavaCodeSandboxTemplate {

    public static void main(String[] args) {
        JavaNativeCodeSandbox sandbox = new JavaNativeCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
        String code = ResourceUtil.readStr("testcode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        ExecuteCodeResponse executeCodeResponse = sandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    protected List<ExecuteMessage> compileAndRun(File userCodeFile, List<String> inputList,
                                                 LanguageConfig config) {
        String srcPath = userCodeFile.getAbsolutePath();
        String workDir = userCodeFile.getParentFile().getAbsolutePath();
        List<ExecuteMessage> result = new ArrayList<>();

        // ① 编译（解释型语言 compileCmdTemplate 为 null，跳过）
        if (config.getCompileCmdTemplate() != null) {
            // E11 编译缓存：源码+语言相同的提交直接复用编译产物，跳过编译
            boolean cacheHit = tryGetFromCompileCache(FileUtil.readUtf8String(srcPath), config,
                    new File(workDir));
            if (!cacheHit) {
                try {
                    Process compileProcess = new ProcessBuilder(
                            LanguageConfigRegistry.render(config.getCompileCmdTemplate(), srcPath, workDir)).start();
                    ExecuteMessage compileMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
                    if (compileMessage.getExitValue() != 0) {
                        // 编译失败：置 compileError 标记，模板整理时整体转为 status=4（编译错误）
                        compileMessage.setCompileError(true);
                        result.add(compileMessage);
                        return result;
                    }
                    saveToCompileCache(FileUtil.readUtf8String(srcPath), config, new File(workDir));
                } catch (IOException e) {
                    // 编译器不存在/不可执行 → 沙箱环境问题，status=2（沙箱错误）
                    throw new RuntimeException("编译进程启动失败: " + e.getMessage(), e);
                }
            }
        }

        // ② 运行：逐用例起子进程，输入写入标准输入，超时上限按语言系数放大（python=10秒）
        long timeOut = computeTimeOut(config);
        for (String inputArgs : inputList) {
            try {
                Process runProcess = new ProcessBuilder(
                        LanguageConfigRegistry.render(config.getRunCmdTemplate(), srcPath, workDir)).start();
                result.add(ProcessUtils.runInteractProcess(runProcess, inputArgs, timeOut, "运行"));
            } catch (IOException e) {
                throw new RuntimeException("运行进程启动失败: " + e.getMessage(), e);
            }
        }
        return result;
    }
}
