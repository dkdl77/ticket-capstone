package com.ticketing.web;

import java.io.IOException;

/** 요청 본문이 제한(16KB)을 넘었을 때 입력 스트림에서 던진다. */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException() {
        super("request body too large");
    }
}
