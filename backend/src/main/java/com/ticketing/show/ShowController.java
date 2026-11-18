package com.ticketing.show;

import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketing.common.ApiError;
import com.ticketing.common.ApiException;
import com.ticketing.common.ErrorCode;

@RestController
@RequestMapping("/api/shows")
public class ShowController {

    private static final Set<String> ALLOWED_QUERY = Set.of("genre", "q", "status", "sort");
    public static final int MAX_QUERY_LENGTH = 40;

    private final ShowService service;

    public ShowController(ShowService service) {
        this.service = service;
    }

    @GetMapping
    public ShowViews.ShowListResponse list(HttpServletRequest request,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {
        // 정의되지 않은 쿼리 파라미터는 거부(오타·조작을 조용히 무시하지 않는다)
        for (String name : request.getParameterMap().keySet()) {
            if (!ALLOWED_QUERY.contains(name)) {
                throw invalid("query", "지원하지 않는 검색 조건입니다: " + safe(name));
            }
        }
        if (q != null && q.trim().length() > MAX_QUERY_LENGTH) {
            throw invalid("q", "검색어는 40자 이내로 입력해 주세요.");
        }
        ShowService.Query query = new ShowService.Query(
                parse(Genre.class, genre, "genre", "장르 값이 올바르지 않습니다."),
                q,
                parse(SaleStatus.class, status, "status", "판매 상태 값이 올바르지 않습니다."),
                parse(ShowService.Sort.class, sort, "sort", "정렬 기준은 date, ranking, opening 중 하나여야 합니다."));
        return new ShowViews.ShowListResponse(service.list(query));
    }

    @GetMapping("/{id}")
    public ShowViews.ShowDetail get(@PathVariable long id) {
        if (id <= 0) {
            throw new ApiException(ErrorCode.SHOW_NOT_FOUND);
        }
        return service.get(id);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(field, message);
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message,
                java.util.List.of(new ApiError.FieldViolation(field, message)));
    }

    private static String safe(String s) {
        return s.matches("^[A-Za-z0-9_]{1,30}$") ? s : "?";
    }
}
