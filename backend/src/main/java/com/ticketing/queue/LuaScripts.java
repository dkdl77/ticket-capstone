package com.ticketing.queue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/** classpath:redis/*.lua 를 읽어 스크립트로 만든다. 대기열 스크립트는 공통 함수(queue-common.lua)를 앞에 붙인다. */
public final class LuaScripts {

    private LuaScripts() {
    }

    @SuppressWarnings("rawtypes")
    public static RedisScript<List> queue(String name) {
        return RedisScript.of(read("queue-common.lua") + "\n" + read(name), List.class);
    }

    public static <T> RedisScript<T> plain(String name, Class<T> resultType) {
        return RedisScript.of(read(name), resultType);
    }

    private static String read(String name) {
        try {
            return new ClassPathResource("redis/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
