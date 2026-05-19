package com.carcolate.agents.response.utils;

public class RedisKeys {

    public static final String COPILOT_TASK = "copilot:task:%s";

    public static String copilotTask(String uuid) {
        return String.format(COPILOT_TASK, uuid);
    }
}
