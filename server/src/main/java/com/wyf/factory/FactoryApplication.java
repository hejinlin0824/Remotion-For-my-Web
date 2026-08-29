package com.wyf.factory;

import com.wyf.factory.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 考研数学讲题视频工厂服务入口。
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class FactoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(FactoryApplication.class, args);
    }
}
