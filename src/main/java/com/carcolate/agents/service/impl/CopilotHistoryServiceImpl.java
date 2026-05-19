package com.carcolate.agents.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.carcolate.agents.domain.CopilotHistory;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.mapper.CopilotHistoryMapper;
import com.carcolate.agents.service.CopilotHistoryService;
import com.github.yitter.idgen.YitIdHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
public class CopilotHistoryServiceImpl extends ServiceImpl<CopilotHistoryMapper, CopilotHistory>
        implements CopilotHistoryService {

    @Override
    public void recordFromSnapshot(TaskSnapshot snap, String userMessage, int llmRound, long costMs) {
        if (snap == null) {
            return;
        }
        try {
            CopilotHistory h = new CopilotHistory();
            h.setId(YitIdHelper.nextId());
            h.setUuid(snap.getUuid());
            h.setAgentId(snap.getAgentId());
            h.setAgentName(snap.getAgentName());
            h.setUserMessage(userMessage);
            h.setFinalReply(snap.getFinalReply());
            h.setStatus(snap.getStatus());
            h.setErrorMsg(snap.getErrorMsg());
            h.setStepCount(snap.getSteps() == null ? 0 : snap.getSteps().size());
            h.setLlmRound(llmRound);
            h.setCostMs(costMs);
            h.setCreatedAt(snap.getCreatedAt() == null ? Instant.now() : snap.getCreatedAt());
            h.setFinishedAt(snap.getFinishedAt() == null ? Instant.now() : snap.getFinishedAt());
            save(h);
        } catch (Exception e) {
            log.error("[CopilotHistory] uuid={} 落库失败", snap.getUuid(), e);
        }
    }
}
