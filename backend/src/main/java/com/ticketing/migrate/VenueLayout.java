package com.ticketing.migrate;

import java.util.List;

/**
 * 시드용 가상 공연장 좌석 배치 템플릿.
 * 좌석 배치는 "가장 긴 행 기준의 격자(rows × seatsPerRow)"이고, 실제 좌석이 없는 칸은 만들지 않는다.
 * 좌석 번호(number)는 그 격자에서의 열 위치(1부터)라서, UI는 seats[]에 없는 (행, 번호)를 빈칸으로 그리면 된다.
 */
public enum VenueLayout {

    /** 소극장: 8행 × 12석 직사각형, 가운데 통로 1개. */
    SMALL(8, 12, List.of(6)) {
        @Override
        boolean exists(int row, int col) {
            return true;
        }
    },

    /** 중형 극장: 12행 × 18석 직사각형, 통로 2개(4|10|4 구역). */
    MEDIUM(12, 18, List.of(4, 14)) {
        @Override
        boolean exists(int row, int col) {
            return true;
        }
    },

    /** 부채꼴: 10행, 앞 두 행 8석 → 두 행마다 2석씩 넓어져 마지막 두 행 16석. 가운데 정렬. */
    FAN(10, 16, List.of(5, 11)) {
        @Override
        boolean exists(int row, int col) {
            int width = Math.min(seatsPerRow(), 8 + 2 * (row / 2));
            int offset = (seatsPerRow() - width) / 2;
            return col > offset && col <= offset + width;
        }
    },

    /** 가장자리 빠짐: 11행 × 16석, 앞 두 행은 양끝 2석씩, 마지막 행은 양끝 1석씩 없음(무대 앞 스피커·뒤쪽 출입구 자리). */
    CUT_CORNERS(11, 16, List.of(4, 12)) {
        @Override
        boolean exists(int row, int col) {
            if (row < 2) {
                return col > 2 && col <= seatsPerRow() - 2;
            }
            if (row == rows() - 1) {
                return col > 1 && col < seatsPerRow();
            }
            return true;
        }
    };

    private final int rows;
    private final int seatsPerRow;
    private final List<Integer> aisleAfter;

    VenueLayout(int rows, int seatsPerRow, List<Integer> aisleAfter) {
        this.rows = rows;
        this.seatsPerRow = seatsPerRow;
        this.aisleAfter = aisleAfter;
    }

    /** row: 0부터(무대에서 가까운 순), col: 1부터(격자 열 위치). */
    abstract boolean exists(int row, int col);

    public int rows() {
        return rows;
    }

    public int seatsPerRow() {
        return seatsPerRow;
    }

    public List<Integer> aisleAfter() {
        return aisleAfter;
    }

    /** 이 배치의 실제 좌석 수. */
    public int seatCount() {
        int n = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 1; c <= seatsPerRow; c++) {
                n += exists(r, c) ? 1 : 0;
            }
        }
        return n;
    }
}
