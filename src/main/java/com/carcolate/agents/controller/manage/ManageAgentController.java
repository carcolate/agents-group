package com.carcolate.agents.controller.manage;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carcolate.agents.config.LangChainConfig;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.mapper.AgentMapper;
import com.carcolate.agents.response.CodeMsg;
import com.carcolate.agents.response.Rsp;
import com.carcolate.agents.service.AgentService;
import com.carcolate.agents.service.RagBaseService;
import com.carcolate.agents.service.RagVectorService;
import com.github.yitter.idgen.YitIdHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("manage/agent")
public class ManageAgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private LangChainConfig langChainConfig;

    @Autowired
    private RagBaseService ragBaseService;

    @Autowired
    private RagVectorService ragVectorService;

    /**
     * 返回配置文件中可用的模型名列表，第一项即为默认模型
     */
    @GetMapping("availableModels")
    public Object availableModels() {
        List<String> models = langChainConfig.getAvailableModels();
        return Rsp.success(models);
    }

    @GetMapping("list")
    public Object list(Agent agent,
                       @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                       @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        Page<Agent> page = new Page<>(pageNum, pageSize);
        var ipage = agentMapper.List(page, agent);
        return Rsp.page(ipage.getRecords(), ipage.getTotal());
    }

    @GetMapping("get")
    public Object get(@RequestParam("id") Long id) {
        Agent a = agentService.getById(id);
        if (a == null) {
            return Rsp.error(CodeMsg.DATA_NULL);
        }
        return Rsp.success(a);
    }

    @PostMapping("add")
    public Object add(@RequestBody Agent agent) {
        if (agent.getName() == null || agent.getName().isBlank()
                || agent.getPrePrompt() == null || agent.getPrePrompt().isBlank()) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        if (!validRuntimeConfig(agent)) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        agent.setId(YitIdHelper.nextId());
        if (agent.getStatus() == null) agent.setStatus(1);
        if (agent.getMaxSteps() == null || agent.getMaxSteps() <= 0) agent.setMaxSteps(6);
        if (agent.getSummarizeLanguage() == null || agent.getSummarizeLanguage().isBlank()) {
            agent.setSummarizeLanguage("zh");
        }
        agent.setCreatedAt(Instant.now());
        agent.setUpdatedAt(Instant.now());
        boolean ok = agentService.save(agent);
        return ok ? Rsp.success(agent) : Rsp.error(CodeMsg.ADD_ERROR);
    }

    @PostMapping("update")
    public Object update(@RequestBody Agent agent) {
        if (agent.getId() == null) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        Agent old = agentService.getById(agent.getId());
        if (old == null) {
            return Rsp.error(CodeMsg.DATA_NULL);
        }
        String effectiveName = agent.getName() == null ? old.getName() : agent.getName();
        String effectivePrePrompt = agent.getPrePrompt() == null ? old.getPrePrompt() : agent.getPrePrompt();
        if (effectiveName == null || effectiveName.isBlank()
                || effectivePrePrompt == null || effectivePrePrompt.isBlank()) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        if (agent.getMaxSteps() != null && (agent.getMaxSteps() < 1 || agent.getMaxSteps() > 20)) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        if (agent.getStatus() != null && agent.getStatus() != 0 && agent.getStatus() != 1) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        if (agent.getSummarizeLanguage() != null && !validLanguage(agent.getSummarizeLanguage())) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        agent.setUpdatedAt(Instant.now());
        boolean ok = agentService.updateById(agent);
        return ok ? Rsp.success(agent) : Rsp.error(CodeMsg.UPDATE_ERROR);
    }

    @Transactional
    @PostMapping("delete")
    public Object delete(@RequestParam("id") Long id) {
        if (id == null || id <= 0 || agentService.getById(id) == null) {
            return Rsp.error(CodeMsg.DATA_NULL);
        }
        ragBaseService.remove(new LambdaQueryWrapper<RagBase>().eq(RagBase::getAgentId, id));
        boolean ok = agentService.removeById(id);
        if (ok) {
            // 删除数据库知识后清理该 Agent 的向量索引。
            ragVectorService.rebuildByAgent(id);
        }
        return ok ? Rsp.success() : Rsp.error(CodeMsg.DELETE_ERROR);
    }

    private boolean validRuntimeConfig(Agent agent) {
        if (agent.getMaxSteps() != null && (agent.getMaxSteps() < 1 || agent.getMaxSteps() > 20)) {
            return false;
        }
        if (agent.getSummarizeLanguage() != null && !validLanguage(agent.getSummarizeLanguage())) {
            return false;
        }
        return agent.getStatus() == null || agent.getStatus() == 0 || agent.getStatus() == 1;
    }

    private boolean validLanguage(String language) {
        String value = language.trim().toLowerCase(Locale.ROOT);
        return "zh".equals(value) || "zh_en".equals(value);
    }
}
