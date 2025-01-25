package com.lian.lianojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.Resource;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import com.lian.lianojcodesandbox.model.ExecuteCodeRequest;
import com.lian.lianojcodesandbox.model.ExecuteCodeResponse;
import com.lian.lianojcodesandbox.model.ExecuteMessage;
import com.lian.lianojcodesandbox.model.JudgeInfo;
import com.lian.lianojcodesandbox.security.DefaultSecurityManager;
import com.lian.lianojcodesandbox.utils.ProcessUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * @author lian
 * @title JavaNativeCodeSandbox
 * @date 2025/1/22 16:57
 * @description Java原生代码沙箱
 */
public class JavaNativeCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final String SECURITY_MANAGER_PATH = "D:\\lyycode02\\lianoj-code-sandbox\\src\\main\\resources\\testcode\\security";

    private static final String SECURITY_MANAGER_CLASS_NAME = "MySecurityManager";

    private static final long TIME_OUT = 5000L;

    private static final List<String> blackList = Arrays.asList("Files", "exec");

    private static final WordTree WORD_TREE;

    static {
        // 初始化字典树
        WORD_TREE = new WordTree();
        WORD_TREE.addWords(blackList);
    }

    public static void main(String[] args) {
        JavaNativeCodeSandbox javaNativeCodeSandbox = new JavaNativeCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "2 3"));
//        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        String code = ResourceUtil.readStr("testCode/unsafeCode/RunFileError.java", StandardCharsets.UTF_8);
        executeCodeRequest.setCode(code);
        executeCodeRequest.setLanguage("java");
        ExecuteCodeResponse executeCodeResponse = javaNativeCodeSandbox.executeCode(executeCodeRequest);
        System.out.println(executeCodeResponse);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
//        System.setSecurityManager(new DefaultSecurityManager());

        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        // 校验用户代码中是否包含黑名单中的关键字
//        FoundWord foundWord = WORD_TREE.match(code);
//        if (foundWord != null) {
//            System.out.println("包含禁止词：" + foundWord.getFoundWord());
//            return null;
//        }

        // 1. 把用户的代码保存为文件
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断目录是否存在，不存在则创建
        if(!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }

        // 把用户的代码隔离存放
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();  // 随机生成文件名
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;   // 生成java文件
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);   // 写入文件

        // 2. 编译用户的代码，生成class文件
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());  // 编译命令
        try {
            Process process = Runtime.getRuntime().exec(compileCmd);  // 执行编译命令
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(process, "编译");  // 获取编译信息
            System.out.println(executeMessage);
        } catch (Exception e) {
            return getErrorResponse(e);
        }

        // 3. 运行用户的代码，得到输出的结果
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
//             String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeParentPath, inputArgs);  // 运行命令
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s;%s -Djava.security.manager=%s Main %s",
                    userCodeParentPath, SECURITY_MANAGER_PATH, SECURITY_MANAGER_CLASS_NAME, inputArgs);  // 运行命令
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);  // 执行运行命令
                // 超时控制
                new Thread(() -> {
                    try {
                        Thread.sleep(TIME_OUT);
                        System.out.println("超时了，中断");
                        runProcess.destroy();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }).start();
                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");  // 获取运行信息
                System.out.println(executeMessage);
                executeMessageList.add(executeMessage);
            } catch (Exception e) {
                return getErrorResponse(e);
            }
        }

        // 4. 收集整理输出结果
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage(); // 错误信息
            if (StrUtil.isNotBlank(errorMessage)) {  //如果有错误信息
                executeCodeResponse.setMessage(errorMessage);
                // 用户提交的代码出现异常
                executeCodeResponse.setStatus(3);
                break;
            }
            outputList.add(executeMessage.getMessage());   // 正确，添加到输出列表
            Long time = executeMessage.getTime();  // 获取时间
            if (time != null) {
                maxTime = Math.max(maxTime, time);  // 取最大时间
            }
        }
        // 正常运行完成
        if (outputList.size() == inputList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        // judgeInfo.setMemory();
        executeCodeResponse.setJudgeInfo(judgeInfo);

        // 5. 文件清理
        if (userCodeFile.getParentFile() != null) {
            boolean del = FileUtil.del(userCodeFile.getParentFile());
            System.out.println("删除" + (del ? "成功" : "失败"));
        }

        return executeCodeResponse;
    }

    /**
     * 获取错误响应
     * @param e
     * @return
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
