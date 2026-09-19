package com.lian.lianojcodesandbox.utils;

import cn.hutool.crypto.digest.DigestUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 请求签名工具（判题服务 ↔ 代码沙箱 的接口鉴权协议）
 *
 * <p>签名协议：
 * <pre>
 *   请求头 X-Timestamp = 当前毫秒时间戳
 *   请求头 X-Sign      = md5( secretKey + "." + 请求体原文 + "." + timestamp )
 * </pre>
 *
 * <p>设计要点：
 * <ol>
 *   <li>密钥不随请求传输，仅参与摘要计算——抓包无法伪造合法请求；</li>
 *   <li>请求体参与签名——传输途中被篡改（如替换成恶意代码）会校验失败；</li>
 *   <li>时间戳 + 容忍窗口实现防重放——超出窗口的旧请求直接拒绝；</li>
 *   <li>使用 {@link MessageDigest#isEqual} 做恒时比较——避免逐字符比较的
 *       时序侧信道（攻击者可通过响应耗时逐位猜测签名）。</li>
 * </ol>
 *
 * <p>取舍说明：未引入 nonce + Redis 的强防重放（沙箱端无 Redis 依赖），
 * 60 秒时间窗在"内网调用 + 无需绝对防重放"的场景下已足够；
 * 若沙箱独立部署暴露公网，应升级 HTTPS + nonce 方案。
 *
 * @author lian
 */
public class SignUtils {

    /**
     * 签名请求头：时间戳
     */
    public static final String HEADER_TIMESTAMP = "X-Timestamp";

    /**
     * 签名请求头：签名值
     */
    public static final String HEADER_SIGN = "X-Sign";

    /**
     * 默认允许的时钟偏移（毫秒）：双方机器时钟存在正常误差，60 秒内视为有效
     */
    public static final long DEFAULT_MAX_CLOCK_SKEW = 60_000L;

    /**
     * 生成签名
     *
     * @param secretKey 共享密钥
     * @param body      请求体原文（必须与实际发送的字节完全一致，否则校验失败）
     * @param timestamp 毫秒时间戳
     * @return 32 位小写 md5 签名
     */
    public static String genSign(String secretKey, String body, long timestamp) {
        String raw = secretKey + "." + body + "." + timestamp;
        return DigestUtil.md5Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验签名（防篡改 + 防重放）
     *
     * @param secretKey     共享密钥
     * @param body          请求体原文
     * @param timestampStr  请求头中的时间戳字符串
     * @param sign          请求头中的签名
     * @param maxClockSkew  允许的时钟偏移（毫秒）
     * @return true = 校验通过
     */
    public static boolean verify(String secretKey, String body, String timestampStr,
                                 String sign, long maxClockSkew) {
        // 1) 头完整性：时间戳或签名缺失直接拒绝
        if (timestampStr == null || sign == null) {
            return false;
        }
        // 2) 时间戳必须是数字（防御格式异常）
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            return false;
        }
        // 3) 防重放：时间戳超出容忍窗口即拒绝
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > maxClockSkew) {
            return false;
        }
        // 4) 防篡改：重算签名并恒时比较
        String expected = genSign(secretKey, body, timestamp);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8));
    }
}
