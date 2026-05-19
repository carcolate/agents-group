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
}
