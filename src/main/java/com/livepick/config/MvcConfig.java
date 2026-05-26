package com.livepick.config;

import com.livepick.utils.BenchmarkUserInterceptor;
import com.livepick.utils.LoginInterceptor;
import com.livepick.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final boolean skipLoginCheck;

    public MvcConfig(
            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
            @Value("${app.benchmark.skip-login-check:false}") boolean skipLoginCheck
    ) {
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
        this.skipLoginCheck = skipLoginCheck;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BenchmarkUserInterceptor())
                .addPathPatterns("/voucher-order/seckill/**")
                .order(-1);

        if (skipLoginCheck) {
            return;
        }

        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                )
                .order(1);

        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate != null) {
            registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                    .addPathPatterns("/**")
                    .order(0);
        }
    }
}
