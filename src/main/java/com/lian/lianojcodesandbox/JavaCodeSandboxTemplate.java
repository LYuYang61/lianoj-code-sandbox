package com.lian.lianojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.dfa.WordTree;
import com.lian.lianojcodesandbox.language.LanguageConfig;
import com.lian.lianojcodesandbox.language.LanguageConfigRegistry;
import com.lian.lianojcodesandbox.model.ExecuteCodeRequest;
import com.lian.lianojcodesandbox.model.ExecuteCodeResponse;
import com.lian.lianojcodesandbox.model.ExecuteMessage;
import com.lian.lianojcodesandbox.model.JudgeInfo;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author lian
 * @title JavaCodeSandboxTemplate
 * @date 2025/2/2 15:33
 * @description Java 代码沙箱模板（E09 重构后）
 *
 * <p>模板方法模式：本类固定"安全检查 → 落盘 → 编译运行 → 整理 → 清理"的执行骨架，
 * "编译和运行怎么落地"（本机进程 or Docker 容器）由子类实现 {@link #compileAndRun}。
 * 语言差异（编译器、运行命令、镜像、时限系数）全部收敛到
 * {@link LanguageConfigRegistry}，与执行骨架彻底解耦——新增语言只需注册配置。
 *
 * <p>整理步骤会统计所有用例的最大耗时与最大内存（内存仅 Docker 实现能采集，
 * 原生进程在 Windows 上无法可靠测量子进程峰值内存，保持 null 并由判题策略兜底）。
 */
@Slf4j
public abstract class JavaCodeSandboxTemplate implements CodeSandbox {

    /** 沙箱工作根目录：所有用户代码的父目录 */
    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    /** 编译缓存目录（E11）：tmpCode/cache/，按 源码hash+语言 复用编译产物 */
    private static final String CACHE_DIR_NAME = "cache";

    /** 基础超时（毫秒），实际值按语言时限系数放大（见 {@link #computeTimeOut}） */
    private static final long BASE_TIME_OUT = 5000L;

    /**
     * 危险关键字黑名单（E03 安全第一道闸）：
     * 提交代码中命中任一关键字即拒绝执行，防止读写宿主文件、执行系统命令、建立网络连接等行为。
     * 采用 hutool DFA 字典树（WordTree）做多模式匹配，时间复杂度 O(文本长度)。
     * <p>定位与取舍：黑名单可被反射/编码/字符串拼接等手段绕过，属于低成本的前置闸门；
     * 真正的安全兜底是进程隔离（-Xmx 限内存）与容器隔离（JavaDockerCodeSandbox）——分层防御。
     */
    private static final List<String> BLACK_LIST = Arrays.asList(
            // 执行系统命令 / 进程操作
            "exec", "ProcessBuilder", "Runtime", "Process",
            // 文件读写（含 NIO 与经典 IO 流）
            "Files", "RandomAccessFile", "FileChannel", "MappedByteBuffer",
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter",
            // 网络访问
            "Socket", "ServerSocket", "HttpClient", "URLConnection",
            // 动态脚本引擎（可间接执行任意代码）
            "ScriptEngine");

    /**
     * 黑名单字典树（不可变，仅在类加载时构建一次）
     */
    private static final WordTree BLACK_LIST_TREE;

    static {
        BLACK_LIST_TREE = new WordTree();
        BLACK_LIST_TREE.addWords(BLACK_LIST);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String code = executeCodeRequest.getCode();
        // 0. 查语言配置：未注册的语言在这里被拒绝（结构化错误，判题端置"危险操作/失败"）
        LanguageConfig config = LanguageConfigRegistry.get(executeCodeRequest.getLanguage());
        // 1. 代码安全检查：命中危险关键字黑名单则直接拒绝，不进入编译执行流程
        checkCodeSafety(code);
        // 2. 把用户的代码保存为文件
        File userCodeFile = saveCodeToFile(code, config);
        try {
            // 3. 编译 + 运行：骨架到此为止，具体如何编译运行由子类按实现方式落地
            //    inputList 容错：前端/调用方可能传 null，统一按空用例列表处理
            List<ExecuteMessage> executeMessageList = compileAndRun(userCodeFile,
                    executeCodeRequest.getInputList() == null
                            ? new ArrayList<>() : executeCodeRequest.getInputList(),
                    config);
            // 4. 收集整理输出结果
            return getOutputResponse(executeMessageList);
        } finally {
            // 5. 文件清理：无论判题成功与否都清理临时目录（放 finally 防泄漏）
            boolean deleted = deleteFile(userCodeFile);
            if (!deleted) {
                log.error("deleteFile error, userCodeFilePath = {}", userCodeFile.getAbsolutePath());
            }
        }
    }

    /**
     * 编译并运行用户代码（步骤 3）——模板留给子类的唯一抽象步骤
     *
     * @param userCodeFile 用户代码文件（已落盘）
     * @param inputList    用例输入列表（由实现写入子进程/容器标准输入）
     * @param config       语言配置（命令模板、镜像、时限系数）
     * @return 每个用例的执行信息（顺序与 inputList 一致）
     */
    protected abstract List<ExecuteMessage> compileAndRun(File userCodeFile, List<String> inputList,
                                                          LanguageConfig config);

    /**
     * 0. 代码安全检查：DFA 字典树匹配危险关键字黑名单
     * 命中时抛出异常，由 MainController 统一转换为 status=2 的结构化错误响应，
     * 判题服务据此将提交置为"危险操作"失败，而不会误入用例比对流程。
     * 子类可重写本方法以追加更严格（或更宽松）的策略，模板骨架不受影响。
     *
     * @param code 用户提交的代码
     */
    protected void checkCodeSafety(String code) {
        // matchAll：返回文本中命中的所有黑名单词
        List<String> matched = BLACK_LIST_TREE.matchAll(code);
        if (matched != null && !matched.isEmpty()) {
            throw new RuntimeException("代码包含被禁止的关键字: " + matched);
        }
    }

    /**
     * 2. 把用户的代码保存为文件（按语言配置决定源码文件名，如 Main.java / main.py）
     *
     * @param code   用户代码
     * @param config 语言配置
     * @return 保存的文件
     */
    public File saveCodeToFile(String code, LanguageConfig config) {
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断全局代码目录是否存在，没有则新建
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        // 把用户的代码隔离存放：UUID 目录保证并发提交互不覆盖
        String userCodeParentPath = globalCodePathName + File.separator + java.util.UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + config.getSourceFileName();
        return FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
    }

    /**
     * E11 编译缓存读取：以 源码SHA-256+语言 为键查找历史编译产物，命中则拷贝进工作目录并跳过编译。
     * 缓存目录 tmpCode/cache/{hash}/，由 {@link #saveToCompileCache} 写入。
     *
     * @param code   源码原文
     * @param config 语言配置
     * @param workDir 本次执行的工作目录（编译产物应出现在这里）
     * @return true = 缓存命中（调用方可跳过编译）
     */
    protected boolean tryGetFromCompileCache(String code, LanguageConfig config, File workDir) {
        File cacheDir = locateCompileCacheDir(code, config);
        if (cacheDir == null || !FileUtil.exist(cacheDir)) {
            return false;
        }
        try {
            for (File cachedFile : FileUtil.ls(cacheDir.getAbsolutePath())) {
                FileUtil.copy(cachedFile, new File(workDir, cachedFile.getName()), true);
            }
            log.info("编译缓存命中: language={}, hash={}", config.getLanguage(), hashOf(code, config));
            return true;
        } catch (Exception e) {
            // 缓存读取失败不影响正确性：退化为正常编译
            log.warn("编译缓存读取失败, 退化编译: {}", e.getMessage());
            return false;
        }
    }

    /**
     * E11 编译缓存写入：把本次编译产物拷入缓存目录，供相同源码的后续提交复用。
     * 并发安全：先写临时目录再原子 move 到最终位置——避免"并发同 hash 提交互相覆盖
     * 写出半成品产物，被后续命中方拷走导致 NoClassDefFoundError"。
     */
    protected void saveToCompileCache(String code, LanguageConfig config, File workDir) {
        try {
            File cacheDir = locateCompileCacheDir(code, config);
            if (cacheDir == null) {
                return;
            }
            File tmpDir = new File(cacheDir.getParentFile(), cacheDir.getName()
                    + ".tmp." + java.util.UUID.randomUUID());
            FileUtil.mkdir(tmpDir);
            for (File produced : FileUtil.ls(workDir.getAbsolutePath())) {
                // 只缓存编译产物（class/exe 等），不缓存源码本身
                if (!produced.getName().equals(config.getSourceFileName())) {
                    FileUtil.copy(produced, new File(tmpDir, produced.getName()), true);
                }
            }
            // 同分区 rename 为原子操作；目标已存在时覆盖（后完成的写者胜出，内容一致）
            if (FileUtil.exist(cacheDir)) {
                FileUtil.del(cacheDir);
            }
            FileUtil.move(tmpDir, cacheDir, true);
        } catch (Exception e) {
            // 缓存写入失败不影响本次判题结果
            log.warn("编译缓存写入失败: {}", e.getMessage());
        }
    }

    /**
     * 定位缓存目录：tmpCode/cache/{sha256(code)-language}；
     * 源码或语言不同 → hash 不同 → 各自独立缓存，天然无失效问题
     */
    private File locateCompileCacheDir(String code, LanguageConfig config) {
        File userDir = new File(System.getProperty("user.dir"));
        return new File(userDir, GLOBAL_CODE_DIR_NAME + File.separator + CACHE_DIR_NAME
                + File.separator + hashOf(code, config));
    }

    private String hashOf(String code, LanguageConfig config) {
        // SHA-256(源码原文 + 语言)：源码一字节之差即视为不同程序
        return DigestUtil.sha256Hex((code + "@" + config.getLanguage()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 本轮执行的超时上限：基础 5 秒按语言时限系数放大（python=2.0 → 10 秒）
     */
    protected long computeTimeOut(LanguageConfig config) {
        return (long) (BASE_TIME_OUT * config.getTimeLimitFactor());
    }

    /**
     * 4. 收集整理输出结果
     * 任一用例有 stderr → 整体 status=3（用户代码运行错误）；
     * 全部正常 → status=1；time/memory 分别取所有用例的最大值（便于与题目限制比较）
     *
     * @param executeMessageList 每个用例的执行信息
     * @return 沙箱响应
     */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        // E06：取内存峰值（仅 Docker 实现会采集；原生实现为 null，判题策略空值兜底）
        Long maxMemory = null;
        for (ExecuteMessage executeMessage : executeMessageList) {
            // 编译失败：整体置为"编译错误"状态（E09 语义细分，判题端置"编译错误"而非 Wrong Answer）
            if (Boolean.TRUE.equals(executeMessage.getCompileError())) {
                executeCodeResponse.setMessage(StrUtil.blankToDefault(executeMessage.getErrorMessage(), "编译失败"));
                executeCodeResponse.setOutputList(new ArrayList<>());
                executeCodeResponse.setStatus(ExecuteCodeResponse.STATUS_COMPILE_ERROR);
                return executeCodeResponse;
            }
            // 超时：整体置为"超时"状态（判题端判 TIME_LIMIT_EXCEEDED 而非 WA；已触发即短路后续用例）
            if (executeMessage.getErrorMessage() != null
                    && executeMessage.getErrorMessage().startsWith("Time Limit Exceeded")) {
                executeCodeResponse.setMessage(executeMessage.getErrorMessage());
                executeCodeResponse.setOutputList(outputList);
                executeCodeResponse.setStatus(ExecuteCodeResponse.STATUS_TIME_LIMIT);
                return executeCodeResponse;
            }
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // 用户提交的代码执行中存在错误
                executeCodeResponse.setStatus(ExecuteCodeResponse.STATUS_RUN_ERROR);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
            Long memory = executeMessage.getMemory();
            if (memory != null) {
                maxMemory = maxMemory == null ? memory : Math.max(maxMemory, memory);
            }
        }
        // 正常运行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(ExecuteCodeResponse.STATUS_SUCCESS);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

    /**
     * 5. 删除临时目录
     * @param userCodeFile 用户代码文件
     * @return 是否删除成功
     */
    public boolean deleteFile(File userCodeFile) {
        if (userCodeFile.getParentFile() != null) {
            String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
            boolean del = FileUtil.del(userCodeParentPath);
            log.info("删除临时目录{}", del ? "成功" : "失败");
            return del;
        }
        return true;
    }
}
