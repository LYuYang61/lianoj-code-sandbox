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

    /**
     * 进程执行内存
     */
    private Long memory;

    /**
     * 是否为编译失败（E09 语义区分）：编译错误与运行错误的处理路径不同——
     * 判题服务把 status=4 置为"编译错误"，把运行错误的输出缺失按比对逻辑判 Wrong Answer
     */
    private Boolean compileError;
}
