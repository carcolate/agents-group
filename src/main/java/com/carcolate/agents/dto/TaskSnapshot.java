package com.carcolate.agents.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskSnapshot {

    private String uuid;
    private Long agentId;
    private String agentName;

    /**
     * RUNNING / SUCCESS / FAILED
     */
    private String status;

    /**
     * 最终回复
     */
    private String finalReply;

    /**
     * 失败原因
     */
    private String errorMsg;

    private Instant createdAt;
    private Instant finishedAt;

    private List<StepRecord> steps = new ArrayList<>();
}
