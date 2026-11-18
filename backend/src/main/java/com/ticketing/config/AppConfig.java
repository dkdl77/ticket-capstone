package com.ticketing.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    /** 시각은 전부 이 Clock에서 얻는다. 테스트에서는 고정·이동 가능한 Clock을 주입한다. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
