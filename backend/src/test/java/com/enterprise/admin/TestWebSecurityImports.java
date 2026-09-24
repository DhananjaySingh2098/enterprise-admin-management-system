package com.enterprise.admin;

import com.enterprise.admin.security.RateLimitFilter;
import com.enterprise.admin.security.RateLimitProperties;
import com.enterprise.admin.security.RateLimitService;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import com.enterprise.admin.config.CorsConfig;
import com.enterprise.admin.config.CorsProperties;
import com.enterprise.admin.config.TimeConfig;
import com.enterprise.admin.security.ApiErrorWriter;
import com.enterprise.admin.security.JwtProperties;
import com.enterprise.admin.security.JwtService;
import com.enterprise.admin.security.PasswordProperties;
import com.enterprise.admin.security.RefreshTokenCookieService;
import com.enterprise.admin.security.RefreshTokenProperties;
import com.enterprise.admin.security.RestAccessDeniedHandler;
import com.enterprise.admin.security.RestAuthenticationEntryPoint;
import com.enterprise.admin.security.SecurityConfig;

/** The production security chain and its collaborators, for use in web slice tests. */
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        ApiErrorWriter.class, CorsConfig.class, TimeConfig.class, JwtService.class, RefreshTokenCookieService.class,
        RateLimitFilter.class, RateLimitService.class})
@EnableConfigurationProperties({CorsProperties.class, JwtProperties.class, RefreshTokenProperties.class,
        PasswordProperties.class, RateLimitProperties.class})
public class TestWebSecurityImports {
}
