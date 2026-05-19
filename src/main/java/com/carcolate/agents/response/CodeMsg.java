package com.carcolate.agents.response;

public class CodeMsg {

    private int code;
    private String msg;

    public CodeMsg(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static CodeMsg SUCCESS = new CodeMsg(0, null);
    public static CodeMsg SERVER_ERROR = new CodeMsg(500, "服务器状态不佳，请稍后再试");
    public static CodeMsg RESOURCE_BUSY = new CodeMsg(501, "目标繁忙，2秒后再试");
    public static CodeMsg PARAM_ERROR = new CodeMsg(400, "参数错误");
    public static CodeMsg DATA_NULL = new CodeMsg(500201, "数据不存在");
    public static CodeMsg DATA_EXIST = new CodeMsg(500202, "数据已存在");
    public static CodeMsg ADD_ERROR = new CodeMsg(500, "添加失败");
    public static CodeMsg UPDATE_ERROR = new CodeMsg(500, "更新失败");
    public static CodeMsg DELETE_ERROR = new CodeMsg(500, "删除失败");
    public static CodeMsg AGENT_NOT_FOUND = new CodeMsg(600, "Agent不存在或已禁用");
    public static CodeMsg TASK_NOT_FOUND = new CodeMsg(601, "任务不存在或已过期");
    public static CodeMsg LLM_ERROR = new CodeMsg(700, "大模型调用异常");

    public static CodeMsg BREAK(String msg, Object... args) {
        return new CodeMsg(500203, String.format(msg, args));
    }

    public static CodeMsg SERVER_RUN_Exception(String err) {
        return new CodeMsg(500004, "业务异常：" + err);
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
