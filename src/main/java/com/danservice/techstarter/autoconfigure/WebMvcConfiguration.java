package com.danservice.techstarter.autoconfigure;

import com.danservice.techstarter.LoggingInterceptorAdapter;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry
                .addInterceptor(new LoggingInterceptorAdapter())
                .addPathPatterns("/**");
    }
}