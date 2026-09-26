package com.lian.lianojcodesandbox.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

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
     * 获取全局唯一的 Docker 客户端（懒加载；默认读环境变量 DOCKER_HOST）
     */
    public DockerClient getDockerClient() {
        if (dockerClient == null) {
            synchronized (this) {
                if (dockerClient == null) {
                    dockerClient = DockerClientBuilder.getInstance().build();
                }
            }
        }
        return dockerClient;
    }

    /**
     * 借用一个已启动的容器：池空则即时创建（退化为非池化路径，行为一致）
     *
     * @param image 容器镜像（来自语言配置）
     * @return 容器 id
     */
    public String acquire(String image) {
        LinkedBlockingDeque<String> pool = pools.computeIfAbsent(image,
                key -> new LinkedBlockingDeque<>());
        String containerId = pool.poll();
        if (containerId != null) {
            log.info("容器池命中: image={}, containerId={}", image, containerId);
            return containerId;
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
        String containerId = containerCmd.exec().getId();
        client.startContainerCmd(containerId).exec();
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
