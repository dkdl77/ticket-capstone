package com.ticketing;

import java.util.Arrays;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 실행 모드 두 가지.
 * <ul>
 *   <li>인자 없음: API 서버. Flyway는 돌리지 않는다.</li>
 *   <li>{@code migrate}: Flyway 마이그레이션 + 시드만 수행하고 종료(K8s Job용). 웹 서버를 띄우지 않는다.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TicketingApplication {

    public static final String MIGRATE_PROFILE = "migrate";

    public static void main(String[] args) {
        if (args.length > 0 && MIGRATE_PROFILE.equals(args[0])) {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(TicketingApplication.class)
                    .profiles(MIGRATE_PROFILE)
                    .web(WebApplicationType.NONE)
                    .run(Arrays.copyOfRange(args, 1, args.length));
            System.exit(SpringApplication.exit(ctx));
        }
        SpringApplication.run(TicketingApplication.class, args);
    }
}
