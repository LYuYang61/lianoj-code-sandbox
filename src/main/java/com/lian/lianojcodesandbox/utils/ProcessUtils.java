package com.lian.lianojcodesandbox.utils;

import cn.hutool.core.date.StopWatch;
import com.lian.lianojcodesandbox.model.ExecuteMessage;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author lian
 * @title ProcessUtils
 * @date 2025/1/22 16:57
 * @description 进程工具类
 */
public class ProcessUtils {

    /**
     * 运行进程并获取消息
     *
     * @param runProcess 运行进程
     * @param opName 操作名称
     * @return
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String opName) {
        ExecuteMessage executeMessage = new ExecuteMessage();

        try {
            StopWatch stopWatch = new StopWatch();  // 计时器
            stopWatch.start();  // 开始计时
            // 等待程序执行，获取错误码
            int exitValue = runProcess.waitFor();  // 等待进程执行
            executeMessage.setExitValue(exitValue);  // 设置退出值
            // 正常退出
            if (exitValue == 0) {
                System.out.println(opName + "成功");
                // 分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream(), StandardCharsets.UTF_8));  // 读取进程的输出（子进程带 -Dfile.encoding=UTF-8，必须按 UTF-8 解码）
                List<String> outputStrList = new ArrayList<>();
                // 逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    outputStrList.add(compileOutputLine);
                }
                executeMessage.setMessage(StringUtils.join(outputStrList, "\n"));

            } else {
                // 异常退出
                System.out.println(opName + "失败，错误码： " + exitValue);
                // 分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream(), StandardCharsets.UTF_8));  // 读取进程的输出（子进程带 -Dfile.encoding=UTF-8，必须按 UTF-8 解码）
                List<String> outputStrList = new ArrayList<>();
                // 逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    outputStrList.add(compileOutputLine);
                }
                executeMessage.setMessage(StringUtils.join(outputStrList, "\n"));

                // 分批获取进程的错误输出
                BufferedReader errorBufferedReader = new BufferedReader(new InputStreamReader(runProcess.getErrorStream(), StandardCharsets.UTF_8));  // 读取进程的错误输出
                StringBuilder errorCompileOutputStringBuilder = new StringBuilder();  // 错误输出

                // 逐行读取
                List<String> errorOutputStrList = new ArrayList<>();
                // 逐行读取
                String errorCompileOutputLine;
                while ((errorCompileOutputLine = errorBufferedReader.readLine()) != null) {
                    errorOutputStrList.add(errorCompileOutputLine);
                }
                executeMessage.setErrorMessage(StringUtils.join(errorOutputStrList, "\n"));   // 设置错误消息
            }
            stopWatch.stop();  // 停止计时
            executeMessage.setTime(stopWatch.getTotalTimeMillis());  // 设置时间
        } catch (Exception e) {
            e.printStackTrace();
        }
        return executeMessage;
    }

    /**
     * 交互式执行进程并获取消息：把用例输入写入子进程的标准输入。
     * OJ 用户代码统一用 Scanner(System.in) 读输入，输入必须走标准输入，拼在命令行参数里对它无效。
     *
     * @param runProcess 运行进程
     * @param args       用例输入（写入子进程标准输入的内容）
     * @param timeOut    最长执行时间（毫秒），超时强制中断，防止恶意代码卡死沙箱
     * @param opName     操作名称
     * @return 执行信息
     */
    public static ExecuteMessage runInteractProcess(Process runProcess, String args, long timeOut, String opName) {
        ExecuteMessage executeMessage = new ExecuteMessage();

        try {
            // 必须先写输入、写完关闭流：子进程的 Scanner 读到 EOF 才能确认输入结束，否则会永久阻塞
            OutputStream outputStream = runProcess.getOutputStream();
            outputStream.write((args + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            outputStream.close();

            StopWatch stopWatch = new StopWatch();  // 计时器
            stopWatch.start();  // 开始计时
            // 带超时地等待进程结束：超时才销毁并打印，不会出现"进程早已结束还打印超时"的假日志
            boolean finished = runProcess.waitFor(timeOut, TimeUnit.MILLISECONDS);
            if (!finished) {
                System.out.println(opName + "超时了，中断");
                runProcess.destroy();
                // destroy() 发送的是终止信号（Windows=kill / Linux=SIGTERM），
                // 忽略信号或派生子进程的程序可能不退出——3 秒后强制升级为 kill -9
                if (!runProcess.waitFor(3, TimeUnit.SECONDS)) {
                    runProcess.destroyForcibly();
                    runProcess.waitFor();
                }
                // 显式标记超时：整理阶段据此把响应置为 status=5（判题端判"超时"而非 WA）
                executeMessage.setErrorMessage("Time Limit Exceeded（沙箱超时强制中断）");
            }
            int exitValue = runProcess.exitValue();  // 获取错误码
            executeMessage.setExitValue(exitValue);  // 设置退出值
            // 正常退出
            if (exitValue == 0) {
                System.out.println(opName + "成功");
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream(), StandardCharsets.UTF_8));  // 读取进程的输出
                List<String> outputStrList = new ArrayList<>();
                String outputLine;
                while ((outputLine = bufferedReader.readLine()) != null) {
                    outputStrList.add(outputLine);
                }
                executeMessage.setMessage(StringUtils.join(outputStrList, "\n"));
            } else {
                // 异常退出
                System.out.println(opName + "失败，错误码： " + exitValue);
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream(), StandardCharsets.UTF_8));  // 读取进程的输出
                List<String> outputStrList = new ArrayList<>();
                String outputLine;
                while ((outputLine = bufferedReader.readLine()) != null) {
                    outputStrList.add(outputLine);
                }
                executeMessage.setMessage(StringUtils.join(outputStrList, "\n"));

                BufferedReader errorBufferedReader = new BufferedReader(new InputStreamReader(runProcess.getErrorStream(), StandardCharsets.UTF_8));  // 读取进程的错误输出
                List<String> errorOutputStrList = new ArrayList<>();
                String errorOutputLine;
                while ((errorOutputLine = errorBufferedReader.readLine()) != null) {
                    errorOutputStrList.add(errorOutputLine);
                }
                executeMessage.setErrorMessage(StringUtils.join(errorOutputStrList, "\n"));  // 设置错误消息
            }
            stopWatch.stop();  // 停止计时
            executeMessage.setTime(stopWatch.getTotalTimeMillis());  // 设置时间
        } catch (Exception e) {
            e.printStackTrace();
        }
        return executeMessage;
    }
}
