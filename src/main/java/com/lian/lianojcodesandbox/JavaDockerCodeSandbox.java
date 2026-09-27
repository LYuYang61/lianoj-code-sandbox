package com.lian.lianojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import com.github.dockerjava.api.DockerClient;
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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
            // ② 拷贝前把各用例输入落成文件，随工作目录一起拷入：
            //    docker-java 3.3.0 的 exec 在 Windows 命名管道上写入 stdin 会损坏连接
            //    （真机探针结论：hijack 流 + stdin 必现"管道已结束"），
            //    改为"输入文件 + sh -c 重定向"——exec 只回传 stdout/stderr 单向流，稳定可靠
            for (int i = 0; i < inputList.size(); i++) {
                FileUtil.writeUtf8String(inputList.get(i) + "\n",
                        workDir + "/input_" + i + ".txt");
            }
            // ③ 把工作目录拷入容器 /app：
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
                        compileCmd, null, computeTimeOut(config));
                if (compileMessage.getExitValue() == null || compileMessage.getExitValue() != 0) {
                    compileMessage.setCompileError(true);
                    result.add(compileMessage);
                    return result;
                }
            }

            // ④ 逐用例运行：输入经 /app/input_N.txt 重定向到 stdin，超时上限按语言系数放大；
            //    某用例超时后同容器内僵尸进程会污染后续统计——剩余用例直接标超时短路
            List<String> runCmd = LanguageConfigRegistry.render(dockerRunTemplate(config),
                    containerSrc, CONTAINER_WORK_DIR);
            long timeOut = computeTimeOut(config);
            boolean timedOut = false;
            for (int i = 0; i < inputList.size(); i++) {
                if (timedOut) {
                    ExecuteMessage skip = new ExecuteMessage();
                    skip.setErrorMessage("Time Limit Exceeded（前序用例超时，本用例跳过执行）");
                    result.add(skip);
                    continue;
                }
                String inputPath = CONTAINER_WORK_DIR + "/input_" + i + ".txt";
                ExecuteMessage message = execOnce(dockerClient, containerId, runCmd,
                        inputPath, timeOut);
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
            // ⑤ 归还即销毁：保证文件系统纯净，池会按需补建
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
     * 在容器内执行一条命令：限时、采集 stdout/stderr 与容器内存峰值
     * <p>输入传递采用 sh -c 重定向（inputPath 非空时拼接 {@code < /app/input_N.txt}），
     * exec 连接只回传 stdout/stderr——docker-java 3.3.0 在 Windows 命名管道上
     * 向 exec 写 stdin 会损坏连接（真机探针结论），故不再 attachStdin。
     * 内存峰值采样用 noStream 快照轮询（约 100ms 一次的同步请求），
     * 避免 stats 长流与 exec 流并发依赖 npipe 长连接。
     */
    private ExecuteMessage execOnce(DockerClient dockerClient, String containerId,
                                    List<String> runCmd, String inputPath, long timeOut) throws InterruptedException {
        List<String> shCmd = new ArrayList<>();
        shCmd.add("sh");
        shCmd.add("-c");
        shCmd.add(String.join(" ", runCmd)
                + (inputPath == null ? "" : " < " + inputPath));
        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withCmd(shCmd.toArray(new String[0]))
                .withAttachStdout(true)
                .withAttachStderr(true)
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

        // E06 内存峰值采样（stats 流）：daemon 端约 1Hz 推送，峰值口径，字节制。
        // exec 已不含 stdin（单向 stdout/stderr 流），与 stats 流并发在 npipe 上稳定；
        // noStream 快照在 cgroup v2 下单次需约 2 秒，对秒级以内的运行采不到数（真机验证）
        final StatsCB statsCB = new StatsCB();
        StatsCmd statsCmd = dockerClient.statsCmd(containerId);
        statsCmd.exec(statsCB);

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // 修复①：超时单位 MILLISECONDS（原教程误写 MICROSECONDS，等同 5 毫秒）
        // 修复③（E09 前身）：输入不再写 stdin（原实现把输入拼进命令行参数，Scanner 读不到）
        boolean finished = dockerClient.execStartCmd(execId)
                .exec(callback)
                .awaitCompletion(timeOut, java.util.concurrent.TimeUnit.MILLISECONDS);
        stopWatch.stop();
        // 内存采样流用完必须 close，否则回调线程常驻（资源泄漏）
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
        // 修复④（R19，真机）：exec 按帧拼接的输出带末尾换行（println 的 \n 随帧到达），
        // 而原生路径（ProcessUtils 的 readLine + join）天然不含末尾换行——两路格式不一致
        // 会让严格 equals 比对把正确程序误判 WA（切 Docker 后全 WA 的根因）。
        // 此处对齐原生契约：去掉末尾一个行结束符（\n 或 \r\n）。
        String stdoutStr = stdout.toString();
        if (stdoutStr.endsWith("\n")) {
            stdoutStr = stdoutStr.substring(0, stdoutStr.length() - 1);
            if (stdoutStr.endsWith("\r")) {
                stdoutStr = stdoutStr.substring(0, stdoutStr.length() - 1);
            }
        }
        message.setMessage(stdoutStr);
        message.setTime(stopWatch.getTotalTimeMillis());
        // E06 口径换算：容器 memory_stats.usage 单位是字节，题目 memoryLimit 单位是 KB，
        // 统一在本出口换算为 KB 后上报（判题策略直接与 memoryLimit 比较）
        message.setMemory(statsCB.peak / 1024);
        return message;
    }

    /** stats 流内存采样回调（字节口径，累计峰值） */
    private static class StatsCB extends com.github.dockerjava.api.async.ResultCallbackTemplate<StatsCB, Statistics> {
        volatile long peak = 0;

        @Override
        public void onNext(Statistics s) {
            if (s.getMemoryStats() != null && s.getMemoryStats().getUsage() != null) {
                long u = s.getMemoryStats().getUsage();
                if (u > peak) {
                    peak = u;
                }
            }
        }
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
