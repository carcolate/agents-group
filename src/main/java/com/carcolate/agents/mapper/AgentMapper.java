package com.carcolate.agents.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carcolate.agents.domain.Agent;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AgentMapper extends BaseMapper<Agent> {

    List<Agent> List(@Param("p") Agent agent);

    IPage<Agent> List(IPage<Agent> page, @Param("p") Agent agent);
}
