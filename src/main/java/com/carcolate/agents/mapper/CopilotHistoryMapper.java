package com.carcolate.agents.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carcolate.agents.domain.CopilotHistory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CopilotHistoryMapper extends BaseMapper<CopilotHistory> {

    List<CopilotHistory> List(@Param("p") CopilotHistory history);

    IPage<CopilotHistory> List(IPage<CopilotHistory> page, @Param("p") CopilotHistory history);
}
