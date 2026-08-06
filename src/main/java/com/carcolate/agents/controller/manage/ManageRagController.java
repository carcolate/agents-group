package com.carcolate.agents.controller.manage;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.mapper.RagBaseMapper;
import com.carcolate.agents.response.CodeMsg;
import com.carcolate.agents.response.Rsp;
import com.carcolate.agents.service.RagBaseService;
import com.carcolate.agents.service.RagSummaryService;
import com.carcolate.agents.service.RagVectorService;
import com.carcolate.agents.service.AgentService;
import com.github.yitter.idgen.YitIdHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("manage/rag")
public class ManageRagController {

    @Autowired
    private RagBaseService ragBaseService;

    @Autowired
    private RagBaseMapper ragBaseMapper;

    @Autowired
    private RagVectorService ragVectorService;

    @Autowired
    private RagSummaryService ragSummaryService;

    @Autowired
    private AgentService agentService;

    @GetMapping("list")
    public Object list(RagBase ragBase,
                       @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                       @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        Page<RagBase> page = new Page<>(pageNum, pageSize);
        var ipage = ragBaseMapper.List(page, ragBase);
        return Rsp.page(ipage.getRecords(), ipage.getTotal());
    }

    @GetMapping("get")
    public Object get(@RequestParam("id") Long id) {
        RagBase rb = ragBaseService.getById(id);
        if (rb == null) {
            return Rsp.error(CodeMsg.DATA_NULL);
        }
        return Rsp.success(rb);
    }

    @PostMapping("add")
    public Object add(@RequestBody RagBase ragBase) {
        if (!validKnowledge(ragBase, null)) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        ragBase.setId(YitIdHelper.nextId());
        if (ragBase.getStatus() == null) ragBase.setStatus(1);
        if (ragBase.getEngageType() == null) ragBase.setEngageType(RagBase.ENGAGE_RAG_SEARCH);
        // 前置知识库（engageType=2）走全量注入，不需要摘要；其余类型若用户没传 summary 则调 LLM 现场生成
        boolean isPrepend = ragBase.getEngageType() != null
                && ragBase.getEngageType() == RagBase.ENGAGE_PREPEND;
        if (!isPrepend && (ragBase.getSummary() == null || ragBase.getSummary().isBlank())) {
            ragBase.setSummary(ragSummaryService.summarize(ragBase.getTitle(), ragBase.getContent()));
        }
        ragBase.setCreatedAt(Instant.now());
        ragBase.setUpdatedAt(Instant.now());
        boolean ok = ragBaseService.save(ragBase);
        if (ok) {
            ragVectorService.rebuildByAgent(ragBase.getAgentId());
            return Rsp.success(ragBase);
        }
        return Rsp.error(CodeMsg.ADD_ERROR);
    }

    @PostMapping("update")
    public Object update(@RequestBody RagBase ragBase) {
        if (ragBase.getId() == null) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        RagBase old = ragBaseService.getById(ragBase.getId());
        if (old == null) {
            return Rsp.error(CodeMsg.DATA_NULL);
        }
        if (!validKnowledge(ragBase, old)) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        // 前置知识库不需要摘要；AI 自检索类型才走摘要生成逻辑
        Integer effectiveEngage = ragBase.getEngageType() != null
                ? ragBase.getEngageType() : old.getEngageType();
        boolean isPrepend = effectiveEngage != null && effectiveEngage == RagBase.ENGAGE_PREPEND;
        if (!isPrepend) {
            // content 变化时强制重算摘要；其余情况若入参 summary 为空也尝试补一份
            boolean contentChanged = ragBase.getContent() != null
                    && !ragBase.getContent().equals(old.getContent());
            boolean needRegen = contentChanged
                    || ragBase.getSummary() == null || ragBase.getSummary().isBlank();
            if (needRegen) {
                String title = ragBase.getTitle() == null ? old.getTitle() : ragBase.getTitle();
                String content = ragBase.getContent() == null ? old.getContent() : ragBase.getContent();
                String summary = ragSummaryService.summarize(title, content);
                if (summary != null) {
                    ragBase.setSummary(summary);
                }
            }
        }
        ragBase.setUpdatedAt(Instant.now());
        boolean ok = ragBaseService.updateById(ragBase);
        if (ok) {
            ragVectorService.rebuildByAgent(old.getAgentId());
            if (ragBase.getAgentId() != null && !ragBase.getAgentId().equals(old.getAgentId())) {
                ragVectorService.rebuildByAgent(ragBase.getAgentId());
            }
            return Rsp.success(ragBase);
        }
        return Rsp.error(CodeMsg.UPDATE_ERROR);
    }

    @PostMapping("delete")
    public Object delete(@RequestParam("id") Long id) {
        RagBase old = ragBaseService.getById(id);
        if (old == null) {
            return Rsp.error(CodeMsg.DATA_NULL);
        }
        boolean ok = ragBaseService.removeById(id);
        if (ok) {
            ragVectorService.rebuildByAgent(old.getAgentId());
            return Rsp.success();
        }
        return Rsp.error(CodeMsg.DELETE_ERROR);
    }

    @PostMapping("rebuild")
    public Object rebuild(@RequestParam("agentId") Long agentId) {
        ragVectorService.rebuildByAgent(agentId);
        return Rsp.success();
    }

    private boolean validKnowledge(RagBase current, RagBase old) {
        Long agentId = current.getAgentId() == null && old != null ? old.getAgentId() : current.getAgentId();
        String title = current.getTitle() == null && old != null ? old.getTitle() : current.getTitle();
        String content = current.getContent() == null && old != null ? old.getContent() : current.getContent();
        Integer status = current.getStatus() == null && old != null ? old.getStatus() : current.getStatus();
        Integer engageType = current.getEngageType() == null && old != null ? old.getEngageType() : current.getEngageType();
        return agentId != null && agentId > 0 && agentService.getById(agentId) != null
                && title != null && !title.isBlank()
                && content != null && !content.isBlank()
                && (status == null || status == 0 || status == 1)
                && (engageType == null || engageType == RagBase.ENGAGE_RAG_SEARCH || engageType == RagBase.ENGAGE_PREPEND);
    }
}
