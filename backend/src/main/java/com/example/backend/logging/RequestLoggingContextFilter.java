package com.example.backend.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingContextFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACE_ID_HEADER = "X-Amzn-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // なぜ必要か: リクエスト単位の相関情報を早い段階で固定し、下流のログ出力全体で共有できるようにするため。
        final String requestId = resolveRequestId(request);
        final String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));

        // なぜ必要か: MDCへ共通キーを設定し、Controller/Service/ExceptionHandler の全ログを同一キーで検索可能にするため。
        MDC.put("requestId", requestId);
        MDC.put("traceId", traceId);
        MDC.put("path", request.getRequestURI());
        MDC.put("httpMethod", request.getMethod());
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } catch (Exception exception) {
            // なぜ必要か: 未処理例外をレスポンス変換前に捕捉し、5xx調査に必要な最小情報をERRORで残すため。
            log.atError()
                    .setMessage("Unhandled exception during request processing")
                    .addKeyValue("eventType", "ERROR")
                    .addKeyValue("action", "UNHANDLED_EXCEPTION")
                    .addKeyValue("httpStatus", 500)
                    .addKeyValue("exceptionClass", exception.getClass().getSimpleName())
                    .setCause(exception)
                    .log();
            throw exception;
        } finally {
            // なぜ必要か: スレッド再利用時の文脈汚染を防ぎ、別リクエストへ前回の相関情報が漏れないようにするため。
            MDC.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        // なぜ必要か: 上流で付与された相関IDを優先しつつ、欠落時も必ず検索キーを持たせるため。
        final String headerRequestId = request.getHeader(REQUEST_ID_HEADER);
        if (headerRequestId != null && !headerRequestId.isBlank()) {
            return headerRequestId.trim();
        }
        return UUID.randomUUID().toString();
    }

    private String resolveTraceId(String traceHeader) {
        // なぜ必要か: ALB由来の `X-Amzn-Trace-Id` 形式（Root=...;Parent=...）から安定した相関キーを抽出するため。
        if (traceHeader == null || traceHeader.isBlank()) {
            return "n/a";
        }
        final String[] segments = traceHeader.split(";");
        for (String segment : segments) {
            final String trimmedSegment = segment.trim();
            if (trimmedSegment.startsWith("Root=")) {
                return trimmedSegment.substring("Root=".length());
            }
        }
        return traceHeader.trim();
    }
}
