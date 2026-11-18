package com.ticketing.migrate;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.ticketing.TicketingApplication;

/** migrate 모드: Flyway(컨텍스트 기동 중 자동 실행)가 끝난 뒤 시드를 넣는다. 끝나면 main이 프로세스를 종료한다. */
@Component
@Profile(TicketingApplication.MIGRATE_PROFILE)
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final Seeder seeder;
    private final Clock clock;

    public SeedRunner(Seeder seeder, Clock clock) {
        this.seeder = seeder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Seeder.Result result = seeder.seed(clock.instant());
        log.info("마이그레이션·시드 완료: 공연 {}개·회차 {}개 upsert, 새 좌석 {}석, 새 시드 예매 {}건, 시연 계정 {}",
                result.shows(), result.performances(), result.newSeats(), result.newSeedReservations(),
                result.demoAccount() ? "생성·갱신" : "건너뜀(SEED_DEMO_EMAIL·SEED_DEMO_PASSWORD 없음)");
    }
}
