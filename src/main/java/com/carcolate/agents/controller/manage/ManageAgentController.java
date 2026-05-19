package com.carcolate.agents.controller.manage;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.mapper.AgentMapper;
import com.carcolate.agents.response.CodeMsg;
import com.carcolate.agents.response.Rsp;
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
@RequestMapping("manage/agent")
public class ManageAgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentMapper agentMapper;

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
        agent.setId(YitIdHelper.nextId());
        if (agent.getStatus() == null) agent.setStatus(1);
        if (agent.getMaxSteps() == null || agent.getMaxSteps() <= 0) agent.setMaxSteps(6);
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
        agent.setUpdatedAt(Instant.now());
        boolean ok = agentService.updateById(agent);
        return ok ? Rsp.success(agent) : Rsp.error(CodeMsg.UPDATE_ERROR);
    }

    @PostMapping("delete")
    public Object delete(@RequestParam("id") Long id) {
        boolean ok = agentService.removeById(id);
        return ok ? Rsp.success() : Rsp.error(CodeMsg.DELETE_ERROR);
    }
}
