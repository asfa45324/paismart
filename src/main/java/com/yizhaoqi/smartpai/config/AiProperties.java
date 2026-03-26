package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局 AI 相关配置，包含 Prompt 模板和生成参数。
 * 通过 @Component 注解标记为 Spring 组件，使其成为 Spring 容器管理的 Bean。
 * @ConfigurationProperties 注解将配置文件中以 "ai" 为前缀的属性绑定到这个类上。
 * @Data 注解来自 Lombok，自动生成 getter、setter、toString 等方法。
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    // Prompt 配置对象，包含提示词相关的配置项
    private Prompt prompt = new Prompt();
    // 生成参数配置对象，包含 AI 生成文本时的参数设置
    private Generation generation = new Generation();

    /**
     * Prompt 配置内部类
     * 使用 @Data 注解自动生成 getter、setter 等方法
     */
    @Data
    public static class Prompt {
        /** 规则文案 */
        private String rules;
        /** 引用开始分隔符 */
        private String refStart;
        /** 引用结束分隔符 */
        private String refEnd;
        /** 无检索结果时的占位文案 */
        private String noResultText;
    }

    @Data
    public static class Generation {
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 最大输出 tokens */
        private Integer maxTokens = 2000;
        /** nucleus top-p */
        private Double topP = 0.9;
    }
} 