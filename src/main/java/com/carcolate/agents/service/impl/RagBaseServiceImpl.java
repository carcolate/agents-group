package com.carcolate.agents.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.carcolate.agents.domain.RagBase;
import com.carcolate.agents.mapper.RagBaseMapper;
import com.carcolate.agents.service.RagBaseService;
import org.springframework.stereotype.Service;

@Service
public class RagBaseServiceImpl extends ServiceImpl<RagBaseMapper, RagBase> implements RagBaseService {
}
