package com.lian.lianojcodesandbox.model;

import lombok.Data;

/**
 * 进程执行信息
 */
@Data
public class ExecuteMessage {

    /**
     * 进程退出值
     */
    private Integer exitValue;

    /**
     * 进程执行结果
     */
    private String message;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 进程执行时间
     */
    private Long time;
}
