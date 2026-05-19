package com.carcolate.agents.controller.manage;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.mapper.RagBaseMapper;
import com.carcolate.agents.response.CodeMsg;
import com.carcolate.agents.response.Rsp;
import com.carcolate.agents.service.RagBaseService;
import com.carcolate.agents.service.RagVectorService;
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
        if (ragBase.getAgentId() == null || ragBase.getAgentId() <= 0
                || ragBase.getTitle() == null || ragBase.getTitle().isBlank()
                || ragBase.getContent() == null || ragBase.getContent().isBlank()) {
            return Rsp.error(CodeMsg.PARAM_ERROR);
        }
        ragBase.setId(YitIdHelper.nextId());
        if (ragBase.getStatus() == null) ragBase.setStatus(1);
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
}
