package com.tradesim.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** 仅 Servlet 服务装配统一异常处理与 JSON 规则（gateway 是 WebFlux，不受影响）。 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class WebCommonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer safeLongs() {
        var s = new SafeLongSerializer();
        return b -> b.serializerByType(Long.class, s).serializerByType(Long.TYPE, s);
    }
}
