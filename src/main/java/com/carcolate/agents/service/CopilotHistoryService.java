package com.carcolate.agents.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.carcolate.agents.domain.CopilotHistory;
import com.carcolate.agents.dto.TaskSnapshot;

public interface CopilotHistoryService extends IService<CopilotHistory> {

    /**
     * 任务结束时调用：根据最终快照 + 用户原始消息持久化一条历史
     */
    void recordFromSnapshot(TaskSnapshot snap, String userMessage, int llmRound, long costMs);
}
