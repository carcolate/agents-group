package com.carcolate.agents.controller;

import com.carcolate.agents.dto.CopilotRequest;
import com.carcolate.agents.dto.TaskSnapshot;
import com.carcolate.agents.response.CodeMsg;
import com.carcolate.agents.response.Rsp;
import com.carcolate.agents.service.CopilotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Copilot 对话 API
 *
 * <pre>
 * 基础路径：/copilot
 *
 * 接口列表：
 *   POST /copilot/chat        — 提交对话任务，返回 task uuid
 *   GET  /copilot/chat/result — 按 uuid 轮询任务执行轨迹与状态
 * </pre>
 *
 * 工作流程：
 *   1. 前端调用 POST /copilot/chat 提交用户消息，获得 uuid
 *   2. 前端携带 uuid 以 800ms 间隔轮询 GET /copilot/chat/result
 *   3. 后端异步执行 ReAct 循环，逐步产出 StepRecord 并存入 Redis
 *   4. 轮询返回的 status 为 SUCCESS / FAILED 时停止轮询
 */
@Slf4j
@RestController
@RequestMapping("copilot")
public class CopilotController {

    @Autowired
    private CopilotService copilotService;

    /**
     * 提交 Copilot 任务，立即返回 uuid
     *
     * <p>收到请求后校验参数、创建任务快照、保存至 Redis，随后异步启动 ReAct 编排引擎，
     * 接口本身同步返回任务标识 uuid，不阻塞。</p>
     *
     * <p><b>请求示例：</b></p>
     * <pre>{@code
     * POST /copilot/chat
     * Content-Type: application/json
     *
     * {
     *   "agentId": 1,
     *   "currentMessage": "这辆车多少钱？",
     *   "historyMessages": [
     *     { "role": "客户", "content": "你好",                            "time": "2026-05-19 11:30:00" },
     *     { "role": "客户", "image":   "https://x.com/car.jpg",              "time": "2026-05-19 11:30:30" },
     *     { "role": "客户", "content": "就这辆", "image": "https://x.com/car2.jpg", "time": "2026-05-19 11:30:40" },
     *     { "role": "客服", "content": "您好，请问有什么可以帮您",          "time": "2026-05-19 11:31:00" }
     *   ],
     *   "historySummary": "客户前几日咨询过价格，倾向 30 万左右"
     * }
     * }</pre>
     *
     * <p><b>成功响应：</b></p>
     * <pre>{@code
     * HTTP/1.1 200
     * {
     *   "code": 0,
     *   "msg": null,
     *   "data": {
     *     "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
     *   }
     * }
     * }</pre>
     *
     * <p><b>错误响应：</b></p>
     * <pre>{@code
     * // 参数错误
     * { "code": 400, "msg": "参数错误", "data": null }
     *
     * // Agent 不存在或已禁用
     * { "code": 600, "msg": "Agent不存在或已禁用", "data": null }
     *
     * // 服务端异常
     * { "code": 500004, "msg": "业务异常：xxx", "data": null }
     * }</pre>
     *
     * @param request 对话请求体（必填）
     * @return 包含 task uuid 的统一响应
     */
    @PostMapping("chat")
    public Rsp<Map<String, String>> chat(@RequestBody CopilotRequest request) {
        try {
            String uuid = copilotService.submit(request);
            Map<String, String> data = new HashMap<>();
            data.put("uuid", uuid);
            return Rsp.success(data);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return Rsp.error(CodeMsg.PARAM_ERROR);
        } catch (IllegalStateException e) {
            return Rsp.error(CodeMsg.AGENT_NOT_FOUND);
        } catch (Exception e) {
            log.error("[Copilot] chat 提交失败", e);
            return Rsp.error(CodeMsg.SERVER_RUN_Exception(e.getMessage()));
        }
    }

    /**
     * 按 uuid 查询任务执行轨迹与状态
     *
     * <p>前端轮询该接口获取 ReAct 决策轨迹。后端从 Redis 中读取 TaskSnapshot，
     * 其中包含当前已累积的所有 StepRecord 步骤。status 为 RUNNING 时任务仍在执行，
     * 为 SUCCESS / FAILED 时任务已结束。</p>
     *
     * <p><b>请求示例：</b></p>
     * <pre>{@code
     * GET /copilot/chat/result?uuid=a1b2c3d4-e5f6-7890-abcd-ef1234567890
     * }</pre>
     *
     * <p><b>成功响应（运行中）：</b></p>
     * <pre>{@code
     * HTTP/1.1 200
     * {
     *   "code": 0,
     *   "msg": null,
     *   "data": {
     *     "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
     *     "agentId": 1,
     *     "agentName": "售前客服助手",
     *     "status": "RUNNING",
     *     "finalReply": null,
     *     "errorMsg": null,
     *     "createdAt": "2026-05-18T10:00:00Z",
     *     "finishedAt": null,
     *     "steps": [
     *       { "type": "USER_INPUT",  "content": "极石汽车多少钱？",          "toolName": null, "toolArgs": null, "time": "2026-05-18T10:00:00Z" },
     *       { "type": "LLM_THINK",  "content": "用户询问极石汽车价格...",    "toolName": null, "toolArgs": null, "time": "2026-05-18T10:00:01Z" },
     *       { "type": "TOOL_CALL",  "content": "正在查询极石汽车价格...",    "toolName": "ragSearch", "toolArgs": "{\"query\":\"极石汽车 价格\"}", "time": "2026-05-18T10:00:02Z" },
     *       { "type": "TOOL_RESULT","content": "{\"price\":\"29.99万起\"}", "toolName": "ragSearch", "toolArgs": null, "time": "2026-05-18T10:00:03Z" },
     *       { "type": "LLM_THINK",  "content": "根据搜索结果组织回复...",    "toolName": null, "toolArgs": null, "time": "2026-05-18T10:00:04Z" }
     *     ]
     *   }
     * }
     * }</pre>
     *
     * <p><b>成功响应（已完成）：</b></p>
     * <pre>{@code
     * {
     *   "code": 0,
     *   "data": {
     *     "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
     *     "agentId": 1,
     *     "agentName": "售前客服助手",
     *     "status": "SUCCESS",
     *     "finalReply": "极石汽车目前售价为 29.99 万元起，具体配置价格请咨询当地经销商。",
     *     "errorMsg": null,
     *     "createdAt": "2026-05-18T10:00:00Z",
     *     "finishedAt": "2026-05-18T10:00:05Z",
     *     "steps": [
     *       { "type": "USER_INPUT",  "content": "极石汽车多少钱？", ... },
     *       { "type": "LLM_THINK",  "content": "...", ... },
     *       { "type": "TOOL_CALL",  "content": "...", "toolName": "ragSearch", "toolArgs": "{\"query\":\"极石汽车 价格\"}", ... },
     *       { "type": "TOOL_RESULT","content": "...", "toolName": "ragSearch", ... },
     *       { "type": "LLM_THINK",  "content": "...", ... },
     *       { "type": "FINAL_REPLY","content": "极石汽车目前售价为 29.99 万元起...", ... }
     *     ]
     *   }
     * }
     * }</pre>
     *
     * <p><b>错误响应：</b></p>
     * <pre>{@code
     * // 任务不存在或已过期
     * { "code": 601, "msg": "任务不存在或已过期", "data": null }
     * }</pre>
     *
     * @param uuid 任务唯一标识（必填）
     * @return 包含 TaskSnapshot 的统一响应
     */
    @GetMapping("chat/result")
    public Rsp<TaskSnapshot> result(@RequestParam("uuid") String uuid) {
        TaskSnapshot snap = copilotService.get(uuid);
        if (snap == null) {
            return Rsp.error(CodeMsg.TASK_NOT_FOUND);
        }
        return Rsp.success(snap);
    }

    /**
     * 中断指定任务。
     *
     * <p>仅向 Redis 写入中断标记，并立即在 snapshot 上追加一条「已收到中断请求」步骤；
     * 真正终止由后台 ReAct 编排循环在下一轮顶端检测后执行（status -> CANCELLED 并落库）。
     * 因此实际生效时间 = 当前 LLM 调用剩余耗时。</p>
     *
     * <p><b>请求示例：</b></p>
     * <pre>{@code
     * POST /copilot/chat/cancel?uuid=a1b2c3d4-e5f6-7890-abcd-ef1234567890
     * }</pre>
     *
     * <p><b>成功响应：</b></p>
     * <pre>{@code
     * // 已发出中断信号（runner 会在下一轮顶端退出）
     * { "code": 0, "msg": null, "data": { "uuid": "...", "result": "REQUESTED" } }
     * }</pre>
     *
     * <p><b>错误响应：</b></p>
     * <pre>{@code
     * // 任务不存在或已过期
     * { "code": 601, "msg": "任务不存在或已过期", "data": null }
     *
     * // 任务已结束（SUCCESS/FAILED/CANCELLED），无需中断
     * { "code": 602, "msg": "任务已结束，无需中断", "data": null }
     * }</pre>
     *
     * @param uuid 任务唯一标识（必填）
     * @return 中断结果
     */
    @PostMapping("chat/cancel")
    public Rsp<Map<String, String>> cancel(@RequestParam("uuid") String uuid) {
        CopilotService.CancelResult r = copilotService.cancel(uuid);
        switch (r) {
            case NOT_FOUND:
                return Rsp.error(CodeMsg.TASK_NOT_FOUND);
            case ALREADY_FINISHED:
                return Rsp.error(CodeMsg.TASK_ALREADY_FINISHED);
            case REQUESTED:
            default:
                Map<String, String> data = new HashMap<>();
                data.put("uuid", uuid);
                data.put("result", r.name());
                return Rsp.success(data);
        }
    }
}
