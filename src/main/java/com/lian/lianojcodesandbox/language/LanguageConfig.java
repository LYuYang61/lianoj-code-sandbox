package com.lian.lianojcodesandbox.language;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语言执行配置（E09 多语言判题架构的核心）
 *
 * <p>设计思想：判题比对逻辑与语言无关（判题服务侧），"怎么编译、怎么运行"才是语言差异，
 * 因此把语言差异全部收敛到沙箱侧的配置注册表中。新增语言 = 在
 * {@link LanguageConfigRegistry} 注册一个本对象，模板方法骨架与其他语言完全复用。
 *
 * <p>命令模板占位符：
 * <ul>
 *   <li>{@code {srcPath}} —— 源码文件的绝对路径（如 D:/tmpCode/uuid/Main.java）</li>
 *   <li>{@code {workDir}} —— 源码所在工作目录（编译产物输出/运行时 classpath）</li>
 * </ul>
 * 占位符在执行前由模板渲染为真实路径；Docker 模式下渲染为容器内路径（/app/...）。
 *
 * @author lian
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LanguageConfig {

    /** 语言标识（与判题服务 QuestionSubmitLanguageEnum 的 value 对齐：java/c++/python） */
    private String language;

    /** 源码文件名（OJ 约定 Main.*） */
    private String sourceFileName;

    /**
     * 编译命令模板（占位符 {srcPath}）；解释型语言（如 python）为 null 表示无需编译
     */
    private List<String> compileCmdTemplate;

    /**
     * 运行命令模板（占位符 {workDir}；stdin 由交互式执行单独写入，不进命令行）
     */
    private List<String> runCmdTemplate;

    /**
     * Docker 模式的运行命令模板（可选覆盖）：
     * java 的原生模板含宿主机 JDK 绝对路径（自包含工具链），在容器内无效，
     * 因此 java 的 Docker 模板改用镜像内 PATH 上的 java。其余语言两套模板相同可不填。
     */
    private List<String> runCmdTemplateDocker;

    /** Docker 模式使用的镜像 */
    private String dockerImage;

    /**
     * 时限系数：解释型语言运行较慢，判题时限按此放宽（python=2.0，编译型=1.0）。
     * 沙箱侧用于放大进程超时上限；判题服务侧有同款系数表用于放宽题目时限判定。
     */
    private double timeLimitFactor;
}
