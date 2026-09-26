package com.lian.lianojcodesandbox.language;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语言配置注册表（E09）：沙箱支持的 languages 单一登记点
 *
 * <p>新增语言的步骤：
 * <ol>
 *   <li>在本类注册 {@link LanguageConfig}（编译/运行命令模板 + Docker 镜像 + 时限系数）；</li>
 *   <li>Docker 模式确认镜像存在（如 gcc:latest / python:3.11-alpine）；</li>
 *   <li>原生模式确认宿主机装有对应编译器/解释器（g++、python）。</li>
 * </ol>
 * 模板方法骨架、安全检查、输入交互、超时控制全部自动复用，无需改动。
 *
 * <p>Java 的编译/运行命令在注册时动态解析当前 JDK 的绝对路径（见 {@link #resolveJdkBinDir()}），
 * 保持 P0 修复的"自包含工具链"特性：不依赖 PATH，避免多 JDK 版本错配。
 *
 * <p>cpp 的可执行文件统一命名为 {@code main.exe}：Windows 上 g++ -o main.exe 生成
 * 可直接运行的可执行文件；Linux 上生成名为 main.exe 的普通文件同样可执行——
 * 同一份命令模板双平台通用，无需按操作系统分支。
 *
 * @author lian
 */
public final class LanguageConfigRegistry {

    /** 各语言的配置（不可变视图） */
    private static final Map<String, LanguageConfig> CONFIG_MAP = new HashMap<>();

    static {
        // ---------- Java：宿主机自包含 JDK 编译运行 ----------
        String jdkBin = resolveJdkBinDir().getAbsolutePath();
        String javac = jdkBin + File.separator + "javac" + (isWindows() ? ".exe" : "");
        String java = jdkBin + File.separator + "java" + (isWindows() ? ".exe" : "");
        CONFIG_MAP.put("java", LanguageConfig.builder()
                .language("java")
                .sourceFileName("Main.java")
                .compileCmdTemplate(Arrays.asList(javac, "-encoding", "utf-8", "{srcPath}"))
                .runCmdTemplate(Arrays.asList(java, "-Xmx256m", "-Dfile.encoding=UTF-8",
                        "-cp", "{workDir}", "Main"))
                // Docker 模式：镜像内自带 JRE，java 走 PATH（宿主机 JDK 绝对路径在容器内不存在）
                .runCmdTemplateDocker(Arrays.asList("java", "-Xmx256m", "-Dfile.encoding=UTF-8",
                        "-cp", "{workDir}", "Main"))
                .dockerImage("openjdk:8-alpine")
                .timeLimitFactor(1.0)
                .build());

        // ---------- C++：原生模式需宿主机装有 g++（MinGW）；推荐 Docker 模式(gcc 镜像) ----------
        CONFIG_MAP.put("c++", LanguageConfig.builder()
                .language("c++")
                .sourceFileName("main.cpp")
                .compileCmdTemplate(Arrays.asList("g++", "-O2", "-std=c++14", "{srcPath}",
                        "-o", "{workDir}/main.exe"))
                .runCmdTemplate(Collections.singletonList("{workDir}/main.exe"))
                .dockerImage("gcc:latest")
                .timeLimitFactor(1.0)
                .build());

        // ---------- Python：解释执行，无需编译；时限放宽 2 倍 ----------
        CONFIG_MAP.put("python", LanguageConfig.builder()
                .language("python")
                .sourceFileName("main.py")
                .compileCmdTemplate(null)
                .runCmdTemplate(Arrays.asList("python", "{srcPath}"))
                .dockerImage("python:3.11-alpine")
                .timeLimitFactor(2.0)
                .build());
    }

    private LanguageConfigRegistry() {
    }

    /**
     * 按语言取配置
     *
     * @param language 语言标识（java/c++/python）
     * @return 配置
     * @throws IllegalArgumentException 语言未注册（在沙箱侧表现为 status=2 的结构化错误）
     */
    public static LanguageConfig get(String language) {
        LanguageConfig config = CONFIG_MAP.get(language);
        if (config == null) {
            throw new IllegalArgumentException("沙箱暂不支持该语言: " + language);
        }
        return config;
    }

    /**
     * 渲染命令模板：把占位符替换为真实路径
     *
     * @param template 命令模板
     * @param srcPath  源码绝对路径
     * @param workDir  工作目录绝对路径
     * @return 渲染后的命令数组（可直接交给 ProcessBuilder / docker exec）
     */
    public static List<String> render(List<String> template, String srcPath, String workDir) {
        String[] cmd = new String[template.size()];
        for (int i = 0; i < template.size(); i++) {
            cmd[i] = template.get(i)
                    .replace("{srcPath}", srcPath)
                    .replace("{workDir}", workDir);
        }
        return Arrays.asList(cmd);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 定位当前 JDK 的 bin 目录（从原模板迁移，保持 P0 的自包含工具链修复）：
     * JDK 8 的 java.home 指向内嵌 JRE（bin 下没有 javac），需回退到 JDK 根目录。
     */
    private static File resolveJdkBinDir() {
        File home = new File(System.getProperty("java.home"));
        if (!new File(home, "bin" + File.separator + "javac" + (isWindows() ? ".exe" : "")).exists()
                && home.getName().equalsIgnoreCase("jre")) {
            home = home.getParentFile();
        }
        return new File(home, "bin");
    }
}
