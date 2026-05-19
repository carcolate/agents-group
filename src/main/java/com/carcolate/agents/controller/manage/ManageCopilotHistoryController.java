package com.carcolate.agents.controller.manage;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carcolate.agents.domain.CopilotHistory;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.mapper.CopilotHistoryMapper;
import com.carcolate.agents.response.CodeMsg;
import com.carcolate.agents.response.Rsp;
import com.carcolate.agents.service.CopilotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("manage/copilot/history")
public class ManageCopilotHistoryController {

    @Autowired
    private CopilotHistoryMapper copilotHistoryMapper;

    @Autowired
    private CopilotService copilotService;

    /**
     * 调用历史分页查询
     * @param history  查询条件（agentId / status / userMessage 关键字 / uuid）
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页数量
     */
    @GetMapping("list")
    public Object list(CopilotHistory history,
                       @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                       @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        if (pageSize <= 0) pageSize = 20;
        if (pageSize > 200) pageSize = 200;
        Page<CopilotHistory> page = new Page<>(pageNum, pageSize);
        var ipage = copilotHistoryMapper.List(page, history);
        return Rsp.page(ipage.getRecords(), ipage.getTotal());
    }

    /**
     * 详情：直接从 Redis 取 TaskSnapshot 返回完整 ReAct 轨迹
     * Redis 已过期时返回错误
     */
    @GetMapping("detail")
    public Object detail(@RequestParam("uuid") String uuid) {
        TaskSnapshot snap = copilotService.get(uuid);
        if (snap == null) {
            return Rsp.error(CodeMsg.TASK_NOT_FOUND);
        }
        return Rsp.success(snap);
    }
}
