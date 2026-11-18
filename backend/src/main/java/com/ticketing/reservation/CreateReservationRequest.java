package com.ticketing.reservation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/reservations body. v3: 이름·이메일은 계정에서 가져오므로 holdId만 받는다.
 * 정의되지 않은 필드(예: 예전의 name·email)는 Jackson 설정(FAIL_ON_UNKNOWN_PROPERTIES)으로 거부된다.
 */
public record CreateReservationRequest(
        @NotBlank(message = "선택한 좌석 정보가 없습니다. 좌석을 다시 골라 주세요.")
        @Pattern(regexp = "^[A-Za-z0-9_-]{22}$", message = "선택한 좌석 정보가 올바르지 않습니다.")
        String holdId) {
}
