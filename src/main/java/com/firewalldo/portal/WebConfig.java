package com.firewalldo.portal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {
    private final SessionService sessions;

    WebConfig(SessionService sessions) { this.sessions = sessions; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                res.setHeader("Cache-Control", "no-store");
                if (!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD")) {
                    String site = req.getHeader("Sec-Fetch-Site");
                    if (site != null && !site.equals("same-origin") && !site.equals("none")) throw ApiError.forbidden();
                    String type = req.getContentType();
                    if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type))) {
                        throw ApiError.invalid("Use Content-Type application/json.");
                    }
                }
                return true;
            }
        }).addPathPatterns("/api/**");
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                req.setAttribute("actor", sessions.actor(req));
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/login", "/api/auth/logout", "/api/auth/activate", "/api/health");
    }
}
