package com.carcolate.agents;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@SpringBootApplication
@MapperScan("com.carcolate.agents.mapper")
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
public class AgentGroupApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentGroupApplication.class, args);
    }

}
