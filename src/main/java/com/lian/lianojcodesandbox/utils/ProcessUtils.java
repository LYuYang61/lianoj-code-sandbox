package com.lian.lianojcodesandbox.utils;

import cn.hutool.core.date.StopWatch;
import com.lian.lianojcodesandbox.model.ExecuteMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;

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
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream()));  // 读取进程的输出
                StringBuilder compileOutputStringBuilder = new StringBuilder();  // 编译输出
                // 逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    compileOutputStringBuilder.append(compileOutputLine).append("\n");
                }
                executeMessage.setMessage(compileOutputStringBuilder.toString());  // 设置消息

            } else {
                // 异常退出
                System.out.println(opName + "失败，错误码： " + exitValue);
                // 分批获取进程的正常输出
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(runProcess.getInputStream()));  // 读取进程的输出
                StringBuilder compileOutputStringBuilder = new StringBuilder();  // 编译输出
                // 逐行读取
                String compileOutputLine;
                while ((compileOutputLine = bufferedReader.readLine()) != null) {
                    compileOutputStringBuilder.append(compileOutputLine).append("\n");
                }
                executeMessage.setMessage(compileOutputStringBuilder.toString());  // 设置消息

                // 分批获取进程的错误输出
                BufferedReader errorBufferedReader = new BufferedReader(new InputStreamReader(runProcess.getErrorStream()));  // 读取进程的错误输出
                StringBuilder errorCompileOutputStringBuilder = new StringBuilder();  // 错误输出

                // 逐行读取
                String errorCompileOutputLine;
                while ((errorCompileOutputLine = errorBufferedReader.readLine()) != null) {
                    errorCompileOutputStringBuilder.append(errorCompileOutputLine).append("\n");
                }
                executeMessage.setErrorMessage(errorCompileOutputStringBuilder.toString());  // 设置错误消息
            }
            stopWatch.stop();  // 停止计时
            executeMessage.setTime(stopWatch.getTotalTimeMillis());  // 设置时间
        } catch (Exception e) {
            e.printStackTrace();
        }
        return executeMessage;
    }
}
