package com.carcolate.agents.service;

import com.carcolate.agents.dto.CopilotRequest;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.domain.enums.CopilotTaskType;

public interface CopilotService {

    /**
     * 创建一个 Copilot 任务，立即返回 uuid（任务在后台异步推进）
     */
    default String submit(CopilotRequest request) {
        return submit(CopilotTaskType.CHAT, request);
    }

    /** 创建指定业务类型的异步任务。 */
    String submit(CopilotTaskType taskType, CopilotRequest request);

    /**
     * 按 uuid 获取任务快照
     */
    TaskSnapshot get(String uuid);

    /**
     * 中断任务。<br>
     * 仅在 Redis 写入 cancel 标记，并立即在 snapshot 上追加一条"已收到中断请求"步骤，
     * 真正终止由 {@link CopilotRunner} 在 ReAct 主循环下一轮顶端检测后执行（status -> CANCELLED 并落库）。
     *
     * <p>由于当前轮的 LLM HTTP 调用无法被强行打断，最坏需等到当前轮结束才会真正退出。</p>
     *
     * @return {@link CancelResult} 描述本次请求的结果
     */
    CancelResult cancel(String uuid);

    /**
     * 中断接口返回结果
     */
    enum CancelResult {
        /** 任务不存在 / 已过期 */
        NOT_FOUND,
        /** 任务已结束（SUCCESS / FAILED / CANCELLED），无需中断 */
        ALREADY_FINISHED,
        /** 已发出中断信号，runner 将在下一轮 ReAct 顶端退出 */
        REQUESTED
    }
}
