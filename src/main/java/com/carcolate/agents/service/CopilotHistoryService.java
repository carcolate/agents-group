package com.carcolate.agents.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.carcolate.agents.domain.CopilotHistory;
import com.carcolate.agents.dto.TaskSnapshot;

public interface CopilotHistoryService extends IService<CopilotHistory> {

    /**
     * 任务结束时调用：根据最终快照 + 用户原始消息持久化一条历史
     *
     * @param modelName 本次实际调用的模型名（Agent 设了走自定义，否则回落全局默认）
     * @param userId    调用方用户标识（来自 CopilotRequest.userId，纯记录字段，可为空）
     */
    void recordFromSnapshot(TaskSnapshot snap, String userMessage, String modelName,
                            int llmRound, long costMs, String userId);
}
