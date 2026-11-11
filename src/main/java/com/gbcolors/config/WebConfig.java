package com.gbcolors.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Configures CORS for split GitHub Pages + Oracle hosting and maps public/ static UI files.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${gbcolors.frontend-origin:*}")
    private String frontendOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = "*".equals(frontendOrigin.trim())
                ? new String[]{"*"}
                : Arrays.stream(frontendOrigin.split(",")).map(String::trim).toArray(String[]::new);

        registry.addMapping("/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "Accept")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("file:./public/", "classpath:/static/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
