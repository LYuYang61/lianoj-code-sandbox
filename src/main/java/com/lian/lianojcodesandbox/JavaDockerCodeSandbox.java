package com.lian.lianojcodesandbox;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.WordTree;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.lian.lianojcodesandbox.model.ExecuteCodeRequest;
import com.lian.lianojcodesandbox.model.ExecuteCodeResponse;
import com.lian.lianojcodesandbox.model.ExecuteMessage;
import com.lian.lianojcodesandbox.model.JudgeInfo;
import com.lian.lianojcodesandbox.utils.ProcessUtils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author lian
 * @title JavaDockerCodeSandbox
 * @date 2025/1/26 15:57
 * @description Java Docker 代码沙箱
 */
public class JavaDockerCodeSandbox implements CodeSandbox {
    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final String SECURITY_MANAGER_PATH = "D:\\lyycode02\\lianoj-code-sandbox\\src\\main\\resources\\testcode\\security";

    private static final String SECURITY_MANAGER_CLASS_NAME = "MySecurityManager";

    private static final long TIME_OUT = 5000L;

    private static final Boolean FIRST_INIT = true;

    public static void main(String[] args) {
        JavaDockerCodeSandbox javaNativeCodeSandbox = new JavaDockerCodeSandbox();
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setInputList(Arrays.asList("1 2", "1 3"));
//        String code = ResourceUtil.readStr("testCode/unsafeCode/RunFileError.java", StandardCharsets.UTF_8);
        String code = ResourceUtil.readStr("testCode/simpleCompute/Main.java", StandardCharsets.UTF_8);
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

        // 1. 把用户的代码保存为文件
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断目录是否存在，不存在则创建
        if (!FileUtil.exist(globalCodePathName)) {
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

        // 3. 创建容器，把文件复制到容器内
        // 获取默认的 Docker Client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        // 拉取镜像
        String image = "openjdk:8-alpine";
        if (FIRST_INIT) {
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);   // 拉取镜像
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    System.out.println("下载镜像：" + item.getStatus());
                    super.onNext(item);
                }
            };
            try {
                pullImageCmd
                        .exec(pullImageResultCallback)   // 执行拉取镜像
                        .awaitCompletion();              // 等待完成
            } catch (InterruptedException e) {
                System.out.println("拉取镜像异常");
                throw new RuntimeException(e);
            }
        }

        System.out.println("下载完成");

        // 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();  // 主机配置
        hostConfig.withMemory(100 * 1000 * 1000L);  // 设置内存
        hostConfig.withMemorySwap(0L);  // 设置交换内存
        hostConfig.withCpuCount(1L);    // 设置CPU数量
        hostConfig.withSecurityOpts(Arrays.asList("seccomp=安全管理配置字符串"));       // 设置安全选项
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume("/app")));  // 绑定文件，将用户代码文件夹挂载到容器内
        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)                    // 设置主机配置
                .withNetworkDisabled(true)           // 禁用网络
                .withReadonlyRootfs(true)                     // 设置只读根文件系统
                .withAttachStdin(true)               // 设置标准输入
                .withAttachStderr(true)              // 设置标准错误
                .withAttachStdout(true)              // 设置标准输出
                .withTty(true)
                .exec();
        System.out.println(createContainerResponse);
        String containerId = createContainerResponse.getId();   // 获取容器ID

        // 启动容器
        dockerClient.startContainerCmd(containerId).exec();

        // docker exec keen_blackwell java -cp /app Main 1 3
        // 执行命令并获取结果
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            StopWatch stopWatch = new StopWatch();  // 计时器
            String[] inputArgsArray = inputArgs.split(" ");
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);  // 拼接命令
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)                        // 设置命令
                    .withAttachStderr(true)         // 设置标准错误
                    .withAttachStdin(true)          // 设置标准输入
                    .withAttachStdout(true)         // 设置标准输出
                    .exec();
            System.out.println("创建执行命令：" + execCreateCmdResponse);

            ExecuteMessage executeMessage = new ExecuteMessage();
            final String[] message = {null};
            final String[] errorMessage = {null};
            long time = 0L;
            // 判断是否超时
            final boolean[] isTimeOut = {true};
            String execId = execCreateCmdResponse.getId();   // 获取执行ID
            ExecStartResultCallback execStartResultCallback = new ExecStartResultCallback() { // 执行结果回调
                @Override
                public void onComplete() {
                    // 如果执行完成，则表示没超时
                    isTimeOut[0] = false;
                    super.onComplete();
                }

                @Override
                public void onNext(Frame frame) {
                    StreamType streamType = frame.getStreamType();  // 获取流类型
                    if (StreamType.STDERR.equals(streamType)) {     // 错误流
                        errorMessage[0] = new String(frame.getPayload());  // 获取错误信息
                        System.out.println("输出错误结果：" + errorMessage[0]);

                    } else if (StreamType.STDOUT.equals(streamType)) {    // 输出流
                        message[0] = new String(frame.getPayload());      // 获取输出信息
                        System.out.println("输出结果：" + message[0]);

                    }
                    super.onNext(frame);
                }
            };

            final long[] maxMemory = {0L};

            // 获取占用内存
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);   // 获取容器状态
            ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(new ResultCallback<Statistics>() {  // 获取结果回调
                @Override
                public void onNext(Statistics statistics) {
                    System.out.println("内存占用：" + statistics.getMemoryStats().getUsage());
                    maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);   // 获取最大内存
                }

                @Override
                public void close() throws IOException {

                }

                @Override
                public void onStart(Closeable closeable) {

                }

                @Override
                public void onError(Throwable throwable) {

                }

                @Override
                public void onComplete() {

                }
            });
            statsCmd.exec(statisticsResultCallback);  // 执行获取状态
            try {
                stopWatch.start();  // 计时开始
                dockerClient.execStartCmd(execId)   // 执行命令
                        .exec(execStartResultCallback)  // 执行结果回调
                        .awaitCompletion(TIME_OUT, TimeUnit.MICROSECONDS);      // 等待完成
                stopWatch.stop();   // 计时结束
                time = stopWatch.getLastTaskTimeMillis();   // 获取时间
                statsCmd.close();  // 关闭获取状态
            } catch (InterruptedException e) {
                System.out.println("执行命令异常");
                throw new RuntimeException(e);
            }
            executeMessage.setMessage(message[0]);
            executeMessage.setErrorMessage(errorMessage[0]);
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);
        }


        // 4、封装结果，跟原生实现方式完全一致
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        long maxMemory = 0;
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
            Long memory = executeMessage.getMemory();  // 获取内存
            if (memory != null) {
                maxMemory = Math.max(maxMemory, memory);  // 取最大内存
            }
        }
        // 正常运行完成
        if (outputList.size() == inputList.size()) {
            executeCodeResponse.setStatus(1);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);
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
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }
}
