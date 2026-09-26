package com.lian.lianojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.lian.lianojcodesandbox.container.SandboxContainerPool;
import com.lian.lianojcodesandbox.language.LanguageConfig;
import com.lian.lianojcodesandbox.language.LanguageConfigRegistry;
import com.lian.lianojcodesandbox.model.ExecuteCodeRequest;
import com.lian.lianojcodesandbox.model.ExecuteMessage;
import com.lian.lianojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author lian
 * @title JavaDockerCodeSandbox
 * @date 2025/2/2 15:41
 * @description Docker 容器沙箱实现（E09/E06/E11 重构）
 *
 * <p>执行流程：从容器池借一个已启动容器 → 把工作目录拷入 /app →
 * 非 JVM 语言在容器内编译（gcc 镜像自带 g++）→ 逐用例 exec 运行（输入写 stdin、限时、采内存）→ 归还即销毁。
 *
 * <p>相对教程原版的四处修正（详见学习文档 05 篇 / P1 实现文档）：
 * <ol>
 *   <li>awaitCompletion 的超时单位由 MICROSECONDS 修正为 MILLISECONDS（原写法等同 5 毫秒）；</li>
 *   <li>补齐容器销毁（原实现只创建不删除，容器无限泄漏）；</li>
 *   <li>用例输入由"命令行参数"改为"写 stdin"——与原生沙箱对齐同一份接口契约；</li>
 *   <li>内存统计打通到 ExecuteMessage（容器 RSS 峰值，口径说明见学习文档 05 篇）。</li>
 * </ol>
 */
@Slf4j
@Component
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {

    /** 容器内统一工作目录 */
    private static final String CONTAINER_WORK_DIR = "/app";

    @Resource
    private SandboxContainerPool containerPool;

    @Override
    protected List<ExecuteMessage> compileAndRun(File userCodeFile, List<String> inputList,
                                                 LanguageConfig config) {
        String workDir = userCodeFile.getParentFile().getAbsolutePath();
        DockerClient dockerClient = containerPool.getDockerClient();

        // ① 编译：java 在宿主机编译（复用 E11 编译缓存，宿主机有 JDK8）；
        //    其他语言在容器内编译（镜像自带工具链，宿主机无需安装 g++）
        if ("java".equals(config.getLanguage())) {
            ExecuteMessage compileMessage = compileOnHost(userCodeFile, config);
            if (Boolean.TRUE.equals(compileMessage.getCompileError())) {
                List<ExecuteMessage> result = new ArrayList<>();
                result.add(compileMessage);
                return result;
            }
        }

        String containerId = containerPool.acquire(config.getDockerImage());
        List<ExecuteMessage> result = new ArrayList<>();
        try {
            // ② 把工作目录拷入容器 /app：
            //    withDirChildrenOnly(true) 只拷贝目录内容——docker-java 默认 false 会把
            //    UUID 目录本身也带进去（实际落点 /app/<uuid>/...），导致后续路径全部失效
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withHostResource(workDir)
                    .withRemotePath(CONTAINER_WORK_DIR)
                    .withDirChildrenOnly(true)
                    .exec();
            String containerSrc = CONTAINER_WORK_DIR + "/" + config.getSourceFileName();

            // 非 JVM 语言在容器内编译（gcc 镜像自带 g++）
            if (!"java".equals(config.getLanguage()) && config.getCompileCmdTemplate() != null) {
                List<String> compileCmd = LanguageConfigRegistry.render(
                        config.getCompileCmdTemplate(), containerSrc, CONTAINER_WORK_DIR);
                ExecuteMessage compileMessage = execOnce(dockerClient, containerId,
                        compileCmd, "", computeTimeOut(config));
                if (compileMessage.getExitValue() == null || compileMessage.getExitValue() != 0) {
                    compileMessage.setCompileError(true);
                    result.add(compileMessage);
                    return result;
                }
            }

            // ③ 逐用例运行：输入写 stdin，超时上限按语言系数放大；
            //    某用例超时后同容器内僵尸进程会污染后续统计——剩余用例直接标超时短路
            List<String> runCmd = LanguageConfigRegistry.render(dockerRunTemplate(config),
                    containerSrc, CONTAINER_WORK_DIR);
            long timeOut = computeTimeOut(config);
            boolean timedOut = false;
            for (String inputArgs : inputList) {
                if (timedOut) {
                    ExecuteMessage skip = new ExecuteMessage();
                    skip.setErrorMessage("Time Limit Exceeded（前序用例超时，本用例跳过执行）");
                    result.add(skip);
                    continue;
                }
                ExecuteMessage message = execOnce(dockerClient, containerId, runCmd,
                        inputArgs + "\n", timeOut);
                if (message.getErrorMessage() != null
                        && message.getErrorMessage().startsWith("Time Limit Exceeded")) {
                    timedOut = true;
                }
                result.add(message);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("容器执行被中断", e);
        } finally {
            // ④ 归还即销毁：保证文件系统纯净，池会按需补建
            containerPool.release(containerId);
        }
    }

    /**
     * 宿主机编译（java 专用）：E11 编译缓存 + 编译失败标记。
     * 模板提供、原生与 Docker 实现共用——编译失败通过 compileError 标记表达，
     * 调用方检查后决定是否提前结束。
     */
    private ExecuteMessage compileOnHost(File userCodeFile, LanguageConfig config) {
        String srcPath = userCodeFile.getAbsolutePath();
        String workDir = userCodeFile.getParentFile().getAbsolutePath();
        boolean cacheHit = tryGetFromCompileCache(FileUtil.readUtf8String(srcPath), config,
                new File(workDir));
        if (cacheHit) {
            ExecuteMessage hit = new ExecuteMessage();
            hit.setExitValue(0);
            return hit;
        }
        try {
            Process compileProcess = new ProcessBuilder(
                    LanguageConfigRegistry.render(config.getCompileCmdTemplate(), srcPath, workDir)).start();
            ExecuteMessage compileMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            if (compileMessage.getExitValue() != 0) {
                compileMessage.setCompileError(true);
                return compileMessage;
            }
            saveToCompileCache(FileUtil.readUtf8String(srcPath), config, new File(workDir));
            return compileMessage;
        } catch (IOException e) {
            throw new RuntimeException("编译进程启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在容器内执行一条命令：写 stdin、限时、采集 stdout/stderr 与容器内存峰值
     */
    private ExecuteMessage execOnce(DockerClient dockerClient, String containerId,
                                    List<String> cmd, String stdin, long timeOut) throws InterruptedException {
        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd.toArray(new String[0]))
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withAttachStdin(true)
                .exec();
        String execId = execCreate.getId();

        ExecuteMessage message = new ExecuteMessage();
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        ExecStartResultCallback callback = new ExecStartResultCallback() {
            @Override
            public void onNext(Frame frame) {
                if (StreamType.STDERR.equals(frame.getStreamType())) {
                    stderr.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                } else {
                    stdout.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
                super.onNext(frame);
            }
        };

        // E06 内存统计：容器 RSS 峰值（含 JVM 基线开销，口径说明见学习文档 05 篇）
        final long[] maxMemory = {0L};
        ResultCallback<Statistics> statsCallback = new ResultCallback<Statistics>() {
            @Override
            public void onNext(Statistics statistics) {
                Long usage = statistics.getMemoryStats() == null ? null
                        : statistics.getMemoryStats().getUsage();
                if (usage != null) {
                    maxMemory[0] = Math.max(maxMemory[0], usage);
                }
            }

            @Override
            public void close() {
            }

            @Override
            public void onStart(java.io.Closeable closeable) {
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        };

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // 绑定内存采样（E06）：保留 statsCmd 引用，用完 close 该实例关闭采样流
        StatsCmd statsCmd = dockerClient.statsCmd(containerId);
        statsCmd.exec(statsCallback);
        // 修复①：超时单位 MILLISECONDS（原教程误写 MICROSECONDS，等同 5 毫秒）
        // 修复③：输入写 stdin（原实现把输入拼进命令行参数，Scanner 代码读不到）
        boolean finished = dockerClient.execStartCmd(execId)
                .withStdIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)))
                .exec(callback)
                .awaitCompletion(timeOut, java.util.concurrent.TimeUnit.MILLISECONDS);
        stopWatch.stop();
        // 内存采样流用完必须关闭，否则回调线程常驻（资源泄漏）
        statsCmd.close();
        if (!finished) {
            // 超时：TLE 文案必须保留——后面不能再用（通常为空的）stderr 覆盖，否则超时被静默判成成功
            message.setErrorMessage("Time Limit Exceeded（沙箱超时强制中断）");
        } else {
            message.setErrorMessage(stderr.toString());
        }
        // 修复②：执行完查询退出码
        Boolean running = dockerClient.inspectExecCmd(execId).exec().isRunning();
        if (Boolean.FALSE.equals(running)) {
            Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            message.setExitValue(exitCode == null ? null : exitCode.intValue());
        }
        message.setMessage(stdout.toString());
        message.setTime(stopWatch.getTotalTimeMillis());
        // E06 口径换算：容器 memory_stats.usage 单位是字节，题目 memoryLimit 单位是 KB，
        // 统一在本出口换算为 KB 后上报（判题策略直接与 memoryLimit 比较）
        message.setMemory(maxMemory[0] / 1024);
        return message;
    }

    /**
     * 运行命令模板：java 使用容器内 PATH 的 java（宿主机 JDK 绝对路径在容器内无效）
     */
    private List<String> dockerRunTemplate(LanguageConfig config) {
        return config.getRunCmdTemplateDocker() != null
                ? config.getRunCmdTemplateDocker()
                : config.getRunCmdTemplate();
    }
}
