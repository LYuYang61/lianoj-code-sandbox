package com.lian.lianojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.WordTree;
import com.lian.lianojcodesandbox.model.ExecuteCodeRequest;
import com.lian.lianojcodesandbox.model.ExecuteCodeResponse;
import com.lian.lianojcodesandbox.model.ExecuteMessage;
import com.lian.lianojcodesandbox.model.JudgeInfo;
import com.lian.lianojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;


/**
 * @author lian
 * @title JavaCodeSandboxTemplate
 * @date 2025/2/2 15:33
 * @description Java代码沙箱模板
 */
@Slf4j
public abstract class JavaCodeSandboxTemplate implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final long TIME_OUT = 5000L;

    /**
     * 危险关键字黑名单（E03 安全第一道闸）：
     * 提交代码中命中任一关键字即拒绝执行，防止读写宿主文件、执行系统命令、建立网络连接等行为。
     * 采用 hutool DFA 字典树（WordTree）做多模式匹配，时间复杂度 O(文本长度)，远优于逐词 contains。
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

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    /**
     * 定位当前 JDK 的 bin 目录：JDK 8 的 java.home 指向内嵌 JRE（bin 下没有 javac），需回退到 JDK 根目录。
     * 编译和运行固定用同一套 JDK 自带的 javac/java，不依赖 PATH——
     * 否则本机装多个 JDK 时可能出现"javac 21 编译出 v65 字节码、java 8 运行报 UnsupportedClassVersionError"。
     */
    private static File resolveJdkBinDir() {
        File home = new File(System.getProperty("java.home"));
        if (!new File(home, "bin" + File.separator + "javac" + (IS_WINDOWS ? ".exe" : "")).exists()
                && home.getName().equalsIgnoreCase("jre")) {
            home = home.getParentFile();
        }
        return new File(home, "bin");
    }

    private static String getJavacExecutable() {
        return new File(resolveJdkBinDir(), "javac" + (IS_WINDOWS ? ".exe" : "")).getAbsolutePath();
    }

    private static String getJavaExecutable() {
        return new File(resolveJdkBinDir(), "java" + (IS_WINDOWS ? ".exe" : "")).getAbsolutePath();
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        // 0. 代码安全检查（E03）：命中危险关键字黑名单则直接拒绝，不进入编译执行流程
        checkCodeSafety(code);

        // 1. 把用户的代码保存为文件
        File userCodeFile = saveCodeToFile(code);

        // 2. 编译代码，得到 class 文件
        ExecuteMessage compileFileExecuteMessage = compileFile(userCodeFile);
        System.out.println(compileFileExecuteMessage);

        // 3. 执行代码，得到输出结果
        List<ExecuteMessage> executeMessageList = runFile(userCodeFile, inputList);

        // 4. 收集整理输出结果
        ExecuteCodeResponse outputResponse = getOutputResponse(executeMessageList);

        // 5. 文件清理
        boolean b = deleteFile(userCodeFile);
        if (!b) {
            log.error("deleteFile error, userCodeFilePath = {}", userCodeFile.getAbsolutePath());
        }
        return outputResponse;
    }

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
        java.util.List<String> matched = BLACK_LIST_TREE.matchAll(code);
        if (matched != null && !matched.isEmpty()) {
            throw new RuntimeException("代码包含被禁止的关键字: " + matched);
        }
    }

    /**
     * 1. 把用户的代码保存为文件
     * @param code 用户代码
     * @return 保存的文件
     */
    public File saveCodeToFile(String code) {
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断全局代码目录是否存在，没有则新建
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }

        // 把用户的代码隔离存放
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        return userCodeFile;
    }

    /**
     * 2. 编译用户的代码，生成class文件
     * @param userCodeFile 用户代码文件
     * @return 编译信息
     */
    public ExecuteMessage compileFile(File userCodeFile) {
        try {
            // 用参数列表而非拼接字符串，路径含空格也不会被拆断
            Process compileProcess = new ProcessBuilder(getJavacExecutable(), "-encoding", "utf-8",
                    userCodeFile.getAbsolutePath()).start();
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            if (executeMessage.getExitValue() != 0) {
                throw new RuntimeException("编译错误");
            }
            return executeMessage;
        } catch (Exception e) {
//            return getErrorResponse(e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 3. 运行用户的代码，得到输出的结果
     * @param userCodeFile 用户代码文件
     * @param inputList 输入列表
     * @return 执行信息列表
     */
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();

        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            // 用例输入不拼进命令行，由 runInteractProcess 写入子进程的标准输入（OJ 代码用 Scanner(System.in) 读输入）
            try {
                Process runProcess = new ProcessBuilder(getJavaExecutable(), "-Xmx256m", "-Dfile.encoding=UTF-8",
                        "-cp", userCodeParentPath, "Main").start();
                ExecuteMessage executeMessage = ProcessUtils.runInteractProcess(runProcess, inputArgs, TIME_OUT, "运行");
                System.out.println(executeMessage);
                executeMessageList.add(executeMessage);
            } catch (Exception e) {
                throw new RuntimeException("执行错误", e);
            }
        }
        return executeMessageList;
    }

    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // 用户提交的代码执行中存在错误
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
        }
        // 正常运行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        // 要借助第三方库来获取内存占用，非常麻烦，此处不做实现
//        judgeInfo.setMemory();
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

    /**
     * 5. 删除文件
     * @param userCodeFile 用户代码文件
     * @return 是否删除成功
     */
    public boolean deleteFile(File userCodeFile) {
        if (userCodeFile.getParentFile() != null) {
            String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + (del ? "成功" : "失败"));
            return del;
        }
        return true;
    }

    /**
     * 获取错误响应
     * @param e 异常
     * @return 错误响应
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e){
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
