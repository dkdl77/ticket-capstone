package com.ticketing.health;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** K8s 프로브용. liveness=/healthz(프로세스 생존만), readiness=/readyz(의존성 확인). /api/version은 배포된 이미지 버전 확인용. */
@RestController
public class HealthController {

    private final ReadinessService readiness;
    private final String version;

    public HealthController(ReadinessService readiness, @Value("${APP_VERSION:dev}") String version) {
        this.readiness = readiness;
        this.version = version;
    }

    /** 이미지를 빌드할 때 넣은 버전(APP_VERSION). 롤링 업데이트 중 어느 버전이 응답하는지 확인한다. */
    @GetMapping("/api/version")
    public Map<String, String> version() {
        return Map.of("version", version);
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        if (readiness.isReady()) {
            return ResponseEntity.ok(Map.of("status", "ready"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "unavailable"));
    }
}
