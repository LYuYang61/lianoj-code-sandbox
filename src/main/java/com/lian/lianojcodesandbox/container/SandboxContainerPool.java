package com.lian.lianojcodesandbox.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱容器池（E11）：把"创建+启动容器"的数百毫秒开销从每次判题中移除
 *
 * <p>池化策略（教学取舍，如实说明）：
 * <ul>
 *   <li><b>预热</b>：按镜像预创建 N 个"已启动的空容器"放入队列；</li>
 *   <li><b>借用</b>：判题时从队列取一个现成容器，把源码拷入 /app 后执行；</li>
 *   <li><b>归还即销毁</b>：执行完毕直接删除容器，不回池——用后即弃保证纯净
 *       （上一题的产物不会污染下一题），省掉的是"启动"开销而非"复用"环境；
 *       下一次借用若池空则即时创建（退化为原生流程，正确性不受影响）。</li>
 * </ul>
 *
 * <p>同一镜像共用一个池；队列按镜像名隔离。DockerClient 全局单例，
 * 避免原实现"每次判题都新建客户端"的资源浪费。
 *
 * @author lian
 */
@Component
@Slf4j
public class SandboxContainerPool {

    /** 每个镜像默认预热的容器数 */
    private static final int DEFAULT_PRE_WARM = 2;

    private volatile DockerClient dockerClient;

    /** 按镜像隔离的空闲容器队列 */
    private final Map<String, LinkedBlockingDeque<String>> pools = new ConcurrentHashMap<>();

    /**
     * 获取全局唯一的 Docker 客户端（懒加载）
     * <p>传输层显式使用 zerodep，而非 DockerClientBuilder 默认回退的 Jersey：
     * 本机 Docker Desktop（WSL2 后端）的守护进程通过 Windows 命名管道暴露
     * （docker context "desktop-linux" → npipe:////./pipe/dockerDesktopLinuxEngine，
     * 由 docker-java 的 DockerContextMetaFile 在无 DOCKER_HOST 环境变量时解析），
     * Jersey 不支持 npipe 协议（真机验证报 Unsupported protocol scheme），
     * zerodep 的 NamedPipeSocket 原生支持。配置解析链路保持不变。
     */
    public DockerClient getDockerClient() {
        if (dockerClient == null) {
            synchronized (this) {
                if (dockerClient == null) {
                    DockerClientConfig config =
                            DefaultDockerClientConfig.createDefaultConfigBuilder().build();
                    DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                            .dockerHost(config.getDockerHost())
                            .sslConfig(config.getSSLConfig())
                            .maxConnections(100)
                            .build();
                    dockerClient = DockerClientImpl.getInstance(config, httpClient);
                }
            }
        }
        return dockerClient;
    }

    /**
     * 借用一个已启动的容器：池空则即时创建（退化为非池化路径，行为一致）
     * <p>R20 自愈：池内 ID 可能因外部清理（如手工 docker rm、Docker 重启）而失效——
     * 借出前用 inspectCmd 校验容器存活，死 ID 直接丢弃并现场创建，判题不受影响。
     *
     * @param image 容器镜像（来自语言配置）
     * @return 容器 id
     */
    public String acquire(String image) {
        LinkedBlockingDeque<String> pool = pools.computeIfAbsent(image,
                key -> new LinkedBlockingDeque<>());
        String containerId = pool.poll();
        while (containerId != null) {
            try {
                getDockerClient().inspectContainerCmd(containerId).exec();
                log.info("容器池命中: image={}, containerId={}", image, containerId);
                return containerId;
            } catch (Exception e) {
                log.warn("容器池中的容器已失效(外部删除?), 丢弃并取下一个: containerId={}", containerId);
                containerId = pool.poll();
            }
        }
        return createAndStart(image);
    }

    /**
     * 归还即销毁：判题结束的容器直接删除，保证每次执行的文件系统纯净
     */
    public void release(String containerId) {
        try {
            getDockerClient().removeContainerCmd(containerId).withForce(true).exec();
            log.info("判题容器已销毁: containerId={}", containerId);
        } catch (Exception e) {
            // 销毁失败只记日志（泄漏的容器可由 Docker 侧定期清理），不影响判题结果返回
            log.error("判题容器销毁失败: containerId={}, 原因={}", containerId, e.getMessage());
        }
    }

    /**
     * 预热：为指定镜像预创建若干已启动容器（应用启动或首次判题后调用）
     */
    public void preWarm(String image, int count) {
        LinkedBlockingDeque<String> pool = pools.computeIfAbsent(image,
                key -> new LinkedBlockingDeque<>());
        for (int i = 0; i < count && pool.size() < DEFAULT_PRE_WARM; i++) {
            try {
                pool.offer(createAndStart(image));
            } catch (Exception e) {
                log.warn("容器预热失败(不影响后续按需创建): image={}, 原因={}", image, e.getMessage());
                return;
            }
        }
    }

    /**
     * 创建并启动一个受资源硬限制的判题容器
     */
    private String createAndStart(String image) {
        DockerClient client = getDockerClient();
        CreateContainerCmd containerCmd = client.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        // 内存 300MB：须容纳 java 的 -Xmx256m + JVM 基线，否则容器 cgroup 先于 JVM OOM 击杀，
        // 表现为 exit 137 而非可控的 OutOfMemoryError
        hostConfig.withMemory(300 * 1000 * 1000L);
        // memorySwap 与 memory 相等 = 总限额即 memory（等效禁用 swap）：
        // Docker 语义中 memorySwap=0 表示"未设置"（swap 可用 2×memory），MLE 判定会失真
        hostConfig.withMemorySwap(300 * 1000 * 1000L);
        hostConfig.withCpuCount(1L);                 // 限 1 核
        containerCmd.withHostConfig(hostConfig)
                .withNetworkDisabled(true)           // 断网：用户代码无法访问外部
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true);
        // 保活主进程（真机验证发现的缺陷）：三个判题镜像的默认 CMD 均为一次性命令
        // （temurin entrypoint 脚本跑完即退 / bash、python3 无交互 stdin 即退），
        // 容器会在启动后立刻停止——而本实现靠 exec 在容器内执行命令（exec 要求容器
        // 处于 running 状态），池预热的"空容器"根本活不到被借用时刻。
        containerCmd.withCmd("tail", "-f", "/dev/null");
        String containerId = containerCmd.exec().getId();
        client.startContainerCmd(containerId).exec();
        // 预创建工作目录 /app：archive 提取 API（copyArchiveToContainer）与 docker cp 不同，
        // 目标路径不存在会直接 404（Could not find the file /app）。
        // 必须在"创建时"同步 exec mkdir 并等待完成——若推迟到借用时，按需创建的容器
        // 存在"start 刚返回、mkdir 尚未执行"的竞态窗口（真机验证：C++ 判题 404）。
        // 短命令同步 exec 在 npipe 传输上稳定可用（真机探针结论，见 P1 文档 §10）。
        ExecCreateCmdResponse mkdir = client.execCreateCmd(containerId)
                .withCmd("mkdir", "-p", "/app")
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        boolean mkdirDone;
        try {
            mkdirDone = client.execStartCmd(mkdir.getId())
                    .exec(new ExecStartResultCallback())
                    .awaitCompletion(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.removeContainerCmd(containerId).withForce(true).exec();
            throw new IllegalStateException("判题容器初始化被中断", e);
        }
        if (!mkdirDone) {
            client.removeContainerCmd(containerId).withForce(true).exec();
            throw new IllegalStateException("判题容器初始化超时（mkdir /app）");
        }
        log.info("创建判题容器: image={}, containerId={}", image, containerId);
        return containerId;
    }

    /**
     * 当前池内空闲容器（监控用）
     */
    public List<String> idleContainerIds(String image) {
        LinkedBlockingDeque<String> pool = pools.get(image);
        return pool == null ? java.util.Collections.emptyList()
                : Arrays.asList(pool.toArray(new String[0]));
    }
}
