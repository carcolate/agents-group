package com.carcolate.agents.service;

import com.carcolate.agents.dto.CopilotRequest;
import com.carcolate.agents.dto.TaskSnapshot;

public interface CopilotService {

    /**
     * 创建一个 Copilot 任务，立即返回 uuid（任务在后台异步推进）
     */
    String submit(CopilotRequest request);

    /**
     * 按 uuid 获取任务快照
     */
    TaskSnapshot get(String uuid);
}
