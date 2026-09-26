package com.lian.lianojcodesandbox.controller;

import cn.hutool.json.JSONUtil;
import com.lian.lianojcodesandbox.JavaDockerCodeSandbox;
import com.lian.lianojcodesandbox.JavaNativeCodeSandbox;
import com.lian.lianojcodesandbox.model.ExecuteCodeRequest;
import com.lian.lianojcodesandbox.model.ExecuteCodeResponse;
import com.lian.lianojcodesandbox.utils.SignUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author lian
 * @title MainController
 * @date 2025/1/22 16:40
 * @description 沙箱对外接口：执行用户提交的代码
 *
 * <p>鉴权采用签名协议（见 {@link SignUtils}）：
 * 判题服务发送前对"密钥+请求体+时间戳"计算签名，本接口以原始请求体验签。
 * 注意 @RequestBody 接收 String 以保证参与签名的是字节级原文——
 * 若绑定成对象再重新序列化，字段顺序差异会导致签名对不上。
 */
@Slf4j
@RestController("/")
public class MainController {

    /**
     * 共享密钥：与判题服务侧保持一致；通过配置注入，默认值仅用于本地联调
     */
    @Value("${sandbox.auth.secretKey:secretKey}")
    private String secretKey;

    /**
     * 沙箱实现切换开关（P1 审查补接线）：native=本机进程（默认，快、零依赖），
     * docker=容器隔离（支持内存统计与 c++/python）。判题服务侧的 codesandbox.type
     * 只决定"调不调远程沙箱"，本配置决定"远程沙箱用哪种实现"。
     */
    @Value("${sandbox.type:native}")
    private String sandboxType;

    @Resource
    private JavaNativeCodeSandbox javaNativeCodeSandbox;

    @Resource
    private JavaDockerCodeSandbox javaDockerCodeSandbox;

    @GetMapping("/health")
    public String healthCheck() {
        return "ok";
    }

    /**
     * 按配置选择沙箱实现
     */
    private com.lian.lianojcodesandbox.CodeSandbox resolveSandbox() {
        return "docker".equals(sandboxType) ? javaDockerCodeSandbox : javaNativeCodeSandbox;
    }

    /**
     * 执行用户代码
     *
     * @param rawBody 请求体原文（参与签名校验，随后手工反序列化）
     * @return 执行结果；鉴权失败返回 401 与空 body
     */
    @PostMapping("/executeCode")
    public ExecuteCodeResponse executeCode(@RequestBody String rawBody, HttpServletRequest request,
                                           HttpServletResponse response) {
        // 1) 签名校验：防伪造、防篡改、防重放（协议见 SignUtils）
        String timestamp = request.getHeader(SignUtils.HEADER_TIMESTAMP);
        String sign = request.getHeader(SignUtils.HEADER_SIGN);
        boolean verified = SignUtils.verify(secretKey, rawBody, timestamp, sign,
                SignUtils.DEFAULT_MAX_CLOCK_SKEW);
        if (!verified) {
            log.warn("沙箱请求签名校验失败, timestamp={}", timestamp);
            response.setStatus(401);
            return null;
        }
        // 2) 解析请求参数（签名校验通过后才解析，恶意报文不会进入执行流程）。
        //    解析失败/参数缺失一律返回结构化错误响应（status=2）而非 400/500 错误页——
        //    判题服务会把任何响应体反序列化为 ExecuteCodeResponse，
        //    Spring 默认错误页中的 "status":500 会被误读，导致判题侧状态混乱
        ExecuteCodeRequest executeCodeRequest = null;
        try {
            executeCodeRequest = JSONUtil.toBean(rawBody, ExecuteCodeRequest.class);
        } catch (Exception e) {
            log.warn("沙箱请求参数解析失败: {}", e.getMessage());
        }
        if (executeCodeRequest == null || executeCodeRequest.getCode() == null) {
            log.warn("沙箱请求参数为空或缺少代码");
            return buildSandboxErrorResponse("请求参数为空或缺少代码");
        }
        // 3) 执行代码：任何内部异常都转为结构化错误响应（status=2），
        //    避免直接抛 500 导致判题服务只能拿到空响应而无法区分错误类型
        try {
            return resolveSandbox().executeCode(executeCodeRequest);
        } catch (Exception e) {
            log.error("沙箱执行异常: {}", e.getMessage(), e);
            return buildSandboxErrorResponse("沙箱内部错误: " + e.getMessage());
        }
    }

    /**
     * 构造结构化的沙箱错误响应（status=2），供判题服务识别并置为"危险操作/失败"
     */
    private ExecuteCodeResponse buildSandboxErrorResponse(String message) {
        return ExecuteCodeResponse.builder()
                .outputList(java.util.Collections.emptyList())
                .message(message)
                .status(ExecuteCodeResponse.STATUS_SANDBOX_ERROR)
                .build();
    }
}
