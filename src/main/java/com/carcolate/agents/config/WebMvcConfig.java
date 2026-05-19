package com.carcolate.agents.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/manage-web/**")
                .addResourceLocations("classpath:/static/manage-web/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/manage-web/index.html");
        registry.addRedirectViewController("/manage-web", "/manage-web/index.html");
        registry.addRedirectViewController("/manage-web/", "/manage-web/index.html");
    }
}
