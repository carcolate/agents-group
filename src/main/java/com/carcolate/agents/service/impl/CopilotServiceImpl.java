package com.carcolate.agents.service.impl;

import com.alibaba.fastjson2.JSON;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.domain.enums.StepType;
import com.carcolate.agents.domain.enums.TaskStatus;
import com.carcolate.agents.dto.CopilotRequest;
import com.carcolate.agents.dto.StepRecord;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.response.utils.RedisKeys;
import com.carcolate.agents.service.AgentService;
import com.carcolate.agents.service.CopilotRunner;
import com.carcolate.agents.service.CopilotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CopilotServiceImpl implements CopilotService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private AgentService agentService;

    @Autowired
    private CopilotRunner copilotRunner;

    @Value("${copilot.task.ttl-seconds:86400}")
    private long ttlSeconds;

    @Override
    public String submit(CopilotRequest request) {
        if (request == null || request.getAgentId() == null
                || request.getCurrentMessage() == null || request.getCurrentMessage().isBlank()) {
            throw new IllegalArgumentException("agentId 与 currentMessage 必填");
        }
        Agent agent = agentService.getById(request.getAgentId());
        if (agent == null || agent.getStatus() == null || agent.getStatus() != 1) {
            throw new IllegalStateException("Agent 不存在或已禁用");
        }
        String uuid = UUID.randomUUID().toString();
        TaskSnapshot snap = new TaskSnapshot();
        snap.setUuid(uuid);
        snap.setAgentId(agent.getId());
        snap.setAgentName(agent.getName());
        snap.setStatus(TaskStatus.RUNNING.getCode());
        snap.setCreatedAt(Instant.now());
        snap.getSteps().add(StepRecord.of(StepType.USER_INPUT.getCode(), request.getCurrentMessage()));
        redisTemplate.opsForValue().set(
                RedisKeys.copilotTask(uuid),
                snap,
                ttlSeconds,
                TimeUnit.SECONDS
        );
        copilotRunner.run(uuid, agent, request);
        return uuid;
    }

    @Override
    public TaskSnapshot get(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        Object o = redisTemplate.opsForValue().get(RedisKeys.copilotTask(uuid));
        if (o == null) {
            return null;
        }
        return JSON.parseObject(JSON.toJSONString(o), TaskSnapshot.class);
    }

    @Override
    public CancelResult cancel(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return CancelResult.NOT_FOUND;
        }
        TaskSnapshot snap = get(uuid);
        if (snap == null) {
            return CancelResult.NOT_FOUND;
        }
        String st = snap.getStatus();
        if (!TaskStatus.RUNNING.getCode().equals(st)) {
            // SUCCESS / FAILED / CANCELLED 都视为已结束
            return CancelResult.ALREADY_FINISHED;
        }
        // 1) 写中断标记：runner 在下一轮 ReAct 顶端检测后退出
        redisTemplate.opsForValue().set(
                RedisKeys.copilotCancel(uuid),
                "1",
                ttlSeconds,
                TimeUnit.SECONDS
        );
        // 2) 立即在 snapshot 上落一条"中断请求"步骤，前端轮询能马上看到反馈
        snap.getSteps().add(StepRecord.of(StepType.ERROR.getCode(),
                "已收到中断请求，等待 ReAct 当前轮结束后退出"));
        redisTemplate.opsForValue().set(
                RedisKeys.copilotTask(uuid),
                snap,
                ttlSeconds,
                TimeUnit.SECONDS
        );
        log.info("[Copilot] uuid={} 中断请求已写入", uuid);
        return CancelResult.REQUESTED;
    }
}
