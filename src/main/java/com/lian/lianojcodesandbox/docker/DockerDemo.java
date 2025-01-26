package com.lian.lianojcodesandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.core.DockerClientBuilder;

/**
 * @author lian
 * @title DockerDemo
 * @date 2025/1/25 16:55
 * @description
 */
public class DockerDemo {
    public static void main(String[] args) {
        // 获取默认的 Docker client
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        PingCmd pingCmd = dockerClient.pingCmd();
        pingCmd.exec();
    }
}
