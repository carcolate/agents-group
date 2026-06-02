package com.carcolate.agents.domain.enums;

import com.carcolate.agents.cfg.EnumInterface;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum StepType implements EnumInterface<String> {

    USER_INPUT("USER_INPUT"),
    LLM_THINK("LLM_THINK"),
    TOOL_CALL("TOOL_CALL"),
    TOOL_RESULT("TOOL_RESULT"),
    IMAGE_FETCH("IMAGE_FETCH"),
    STYLE_REWRITE("STYLE_REWRITE"),
    FINAL_REPLY("FINAL_REPLY"),
    ERROR("ERROR");

    private final String value;

    @Override
    public String getCode() {
        return this.value;
    }
}
