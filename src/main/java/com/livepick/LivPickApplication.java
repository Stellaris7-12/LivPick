package com.livepick;

import com.livepick.config.LivPickProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.livepick.mapper")
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LivPickProperties.class)
public class LivPickApplication {

    public static void main(String[] args) {
        SpringApplication.run(LivPickApplication.class, args);
    }

}
