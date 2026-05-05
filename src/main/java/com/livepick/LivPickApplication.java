package com.livepick;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// 注意运行前要在Redis中创建消息队列：127.0.0.1:6379> XGROUP CREATE stream.orders g1 0 MKSTREAM
// 否则VoucherOrderServiceImpl会报错
@MapperScan("com.livepick.mapper")
@SpringBootApplication
public class LivPickApplication {

    public static void main(String[] args) {
        SpringApplication.run(LivPickApplication.class, args);
    }

}
