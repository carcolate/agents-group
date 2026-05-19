package com.carcolate.agents.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carcolate.agents.domain.RagBase;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RagBaseMapper extends BaseMapper<RagBase> {

    List<RagBase> List(@Param("p") RagBase ragBase);

    IPage<RagBase> List(IPage<RagBase> page, @Param("p") RagBase ragBase);
}
