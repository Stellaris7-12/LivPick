package com.livepick.config;

import com.livepick.utils.BenchmarkUserInterceptor;
import com.livepick.utils.LoginInterceptor;
import com.livepick.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${app.benchmark.enabled:false}")
    private boolean benchmarkEnabled;

    @Value("${app.benchmark.skip-login-check:false}")
    private boolean benchmarkSkipLoginCheck;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        if (benchmarkEnabled) {
            registry.addInterceptor(new BenchmarkUserInterceptor())
                    .addPathPatterns("/**")
                    .order(1);
        }

        registry.addInterceptor(new LoginInterceptor(benchmarkEnabled && benchmarkSkipLoginCheck))
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login",
                        "/benchmark/**"
                )
                .order(2);
    }
}
