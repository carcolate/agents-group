package com.carcolate.agents.controller.manage;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.mapper.RagBaseMapper;
import com.carcolate.agents.response.CodeMsg;
import com.carcolate.agents.response.Rsp;
import com.carcolate.agents.service.RagBaseService;
import com.carcolate.agents.service.RagRefreshService;
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

    @Autowired
    private RagRefreshService ragRefreshService;

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
        if (ragBase == null) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        if (ragBase.getAutoRefresh() == null) ragBase.setAutoRefresh(0);
        if (ragBase.getRefreshIntervalMinutes() == null) ragBase.setRefreshIntervalMinutes(60);
        if (!validKnowledge(ragBase, null)) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        ragBase.setId(YitIdHelper.nextId());
        if (ragBase.getStatus() == null) ragBase.setStatus(1);
        if (ragBase.getEngageType() == null) ragBase.setEngageType(RagBase.ENGAGE_RAG_SEARCH);
        try {
            // 开启 URL 自动刷新时，保存动作同步拉取并覆盖正文。
            ragRefreshService.refreshBeforeSave(ragBase);
        } catch (Exception e) {
            return Rsp.error("URL 刷新失败：" + e.getMessage());
        }
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
        RagBase effective = merge(old, ragBase);
        if (!validKnowledge(effective, null)) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        try {
            // 保存动作同步拉取，确保返回成功后正文和向量均已是最新内容。
            ragRefreshService.refreshBeforeSave(effective);
        } catch (Exception e) {
            return Rsp.error("URL 刷新失败：" + e.getMessage());
        }
        // 前置知识库不需要摘要；AI 自检索类型才走摘要生成逻辑
        Integer effectiveEngage = effective.getEngageType();
        boolean isPrepend = effectiveEngage != null && effectiveEngage == RagBase.ENGAGE_PREPEND;
        if (!isPrepend) {
            // content 变化时强制重算摘要；其余情况若入参 summary 为空也尝试补一份
            boolean contentChanged = !effective.getContent().equals(old.getContent());
            boolean needRegen = contentChanged
                    || effective.getSummary() == null || effective.getSummary().isBlank();
            if (needRegen) {
                String summary = ragSummaryService.summarize(effective.getTitle(), effective.getContent());
                if (summary != null) {
                    effective.setSummary(summary);
                }
            }
        }
        effective.setUpdatedAt(Instant.now());
        LambdaUpdateWrapper<RagBase> update = new LambdaUpdateWrapper<>();
        update.eq(RagBase::getId, effective.getId())
                .set(RagBase::getAgentId, effective.getAgentId())
                .set(RagBase::getTitle, effective.getTitle())
                .set(RagBase::getContent, effective.getContent())
                .set(RagBase::getSummary, effective.getSummary())
                .set(RagBase::getEngageType, effective.getEngageType())
                .set(RagBase::getStatus, effective.getStatus())
                .set(RagBase::getUrl, effective.getUrl())
                .set(RagBase::getAutoRefresh, effective.getAutoRefresh())
                .set(RagBase::getRefreshIntervalMinutes, effective.getRefreshIntervalMinutes())
                .set(RagBase::getLastRefreshTime, effective.getLastRefreshTime())
                .set(RagBase::getUpdatedAt, effective.getUpdatedAt());
        boolean ok = ragBaseService.update(update);
        if (ok) {
            ragVectorService.rebuildByAgent(old.getAgentId());
            if (effective.getAgentId() != null && !effective.getAgentId().equals(old.getAgentId())) {
                ragVectorService.rebuildByAgent(effective.getAgentId());
            }
            return Rsp.success(effective);
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
        Integer autoRefresh = current.getAutoRefresh() == null && old != null ? old.getAutoRefresh() : current.getAutoRefresh();
        Integer refreshInterval = current.getRefreshIntervalMinutes() == null && old != null
                ? old.getRefreshIntervalMinutes() : current.getRefreshIntervalMinutes();
        String url = current.getUrl() == null && old != null ? old.getUrl() : current.getUrl();
        boolean remoteContent = Integer.valueOf(1).equals(autoRefresh) && url != null && !url.isBlank();
        return agentId != null && agentId > 0 && agentService.getById(agentId) != null
                && title != null && !title.isBlank()
                && (remoteContent || (content != null && !content.isBlank()))
                && (status == null || status == 0 || status == 1)
                && (autoRefresh == null || autoRefresh == 0 || autoRefresh == 1)
                && (refreshInterval == null || refreshInterval >= 0)
                && (!Integer.valueOf(1).equals(autoRefresh) || (url != null && !url.isBlank()))
                && (engageType == null || engageType == RagBase.ENGAGE_RAG_SEARCH || engageType == RagBase.ENGAGE_PREPEND);
    }

    private RagBase merge(RagBase old, RagBase current) {
        RagBase result = new RagBase();
        result.setId(old.getId());
        result.setAgentId(current.getAgentId() == null ? old.getAgentId() : current.getAgentId());
        result.setTitle(current.getTitle() == null ? old.getTitle() : current.getTitle());
        result.setContent(current.getContent() == null ? old.getContent() : current.getContent());
        result.setSummary(current.getSummary() == null ? old.getSummary() : current.getSummary());
        result.setEngageType(current.getEngageType() == null ? old.getEngageType() : current.getEngageType());
        result.setStatus(current.getStatus() == null ? old.getStatus() : current.getStatus());
        result.setUrl(current.getUrl() == null ? old.getUrl() : current.getUrl());
        result.setAutoRefresh(current.getAutoRefresh() == null ? old.getAutoRefresh() : current.getAutoRefresh());
        result.setRefreshIntervalMinutes(current.getRefreshIntervalMinutes() == null
                ? (old.getRefreshIntervalMinutes() == null ? 60 : old.getRefreshIntervalMinutes())
                : current.getRefreshIntervalMinutes());
        result.setLastRefreshTime(old.getLastRefreshTime());
        result.setCreatedAt(old.getCreatedAt());
        return result;
    }
}
