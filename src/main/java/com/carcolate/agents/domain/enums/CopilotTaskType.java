package com.carcolate.agents.domain.enums;

import lombok.Getter;

/** Copilot 的业务任务类型。 */
@Getter
public enum CopilotTaskType {
    CHAT("chat"),
    SUMMARIZE("summarize"),
    GENERATE("generate");

    private final String code;

    CopilotTaskType(String code) {
        this.code = code;
    }
}
