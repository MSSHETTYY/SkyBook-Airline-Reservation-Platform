package com.skybook.assistant.service;

import com.skybook.common.error.ErrorCode;

/** Assistant-specific error codes; part of the public API contract. */
public enum AssistantErrorCode implements ErrorCode {

    ASSISTANT_UNAVAILABLE(503, "The assistant is temporarily unavailable");

    private final int status;
    private final String title;

    AssistantErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
