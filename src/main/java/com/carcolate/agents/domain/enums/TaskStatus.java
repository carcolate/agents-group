package com.carcolate.agents.domain.enums;

import com.carcolate.agents.cfg.EnumInterface;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum TaskStatus implements EnumInterface<String> {

    RUNNING("RUNNING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED");

    private final String value;

    @Override
    public String getCode() {
        return this.value;
    }
}
