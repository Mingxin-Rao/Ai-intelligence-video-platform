package com.example.server.config;

import com.example.server.interceptor.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    // Add global CORS configuration (resolves Network Error), abandoning local calls
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // Allow all frontend origins
                .allowedOriginPatterns("*")
                // Allow all methods such as GET, POST, DELETE, etc.
                .allowedMethods("*")
                // Allowed request headers (incl. Authorization)
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    // Register the auth interceptor: protects /media/** and /debug/** (/user/** stays public)
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/media/**", "/debug/**");
    }
}
