package com.carcolate.agents.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.carcolate.agents.domain.Agent;
import com.carcolate.agents.mapper.AgentMapper;
import com.carcolate.agents.service.AgentService;
import org.springframework.stereotype.Service;

@Service
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Agent> implements AgentService {
}
