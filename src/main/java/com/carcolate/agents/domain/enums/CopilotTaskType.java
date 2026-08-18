package com.carcolate.agents.domain.enums;

import lombok.Getter;

/** Copilot 的业务任务类型。 */
@Getter
public enum CopilotTaskType {
    CHAT("chat"),
    REBACK("reback"),
    COMPACT("compact"),
    SUMMARIZE("summarize"),
    TAG("tag");

    private final String code;

    CopilotTaskType(String code) {
        this.code = code;
    }
}
