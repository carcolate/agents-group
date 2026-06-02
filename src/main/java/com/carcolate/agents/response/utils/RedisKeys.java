package com.carcolate.agents.response.utils;

public class RedisKeys {

    public static final String COPILOT_TASK = "copilot:task:%s";
    public static final String COPILOT_CANCEL = "copilot:cancel:%s";

    public static String copilotTask(String uuid) {
        return String.format(COPILOT_TASK, uuid);
    }

    /**
     * 任务中断标记 key。CopilotRunner 在 ReAct 每轮顶端检查；存在即代表收到了中断请求。
     */
    public static String copilotCancel(String uuid) {
        return String.format(COPILOT_CANCEL, uuid);
    }
}
