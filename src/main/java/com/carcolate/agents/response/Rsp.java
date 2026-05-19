package com.carcolate.agents.response;

public class Rsp<T> {
    private int code;
    private String msg;
    private T data;
    private T extra;
    private long total;

    public Rsp() {
    }

    private Rsp(T data) {
        this.code = CodeMsg.SUCCESS.getCode();
        this.msg = CodeMsg.SUCCESS.getMsg();
        this.data = data;
    }

    private Rsp(T data, T extra) {
        this.code = CodeMsg.SUCCESS.getCode();
        this.msg = CodeMsg.SUCCESS.getMsg();
        this.data = data;
        this.extra = extra;
    }

    public Rsp(T data, long total) {
        this.data = data;
        this.total = total;
    }

    public Rsp(CodeMsg codeMsg) {
        if (codeMsg != null) {
            this.code = codeMsg.getCode();
            this.msg = codeMsg.getMsg();
        }
    }

    public static <T> Rsp<T> success(T data) {
        return new Rsp<>(data);
    }

    public static <T> Rsp<T> success() {
        return new Rsp<>();
    }

    public static <T> Rsp<T> success(T data, T extra) {
        return new Rsp<>(data, extra);
    }

    public static <T> Rsp<T> page(T data, long total) {
        return new Rsp<>(data, total);
    }

    public static <T> Rsp<T> error(CodeMsg codeMsg) {
        return new Rsp<>(codeMsg);
    }

    public static <T> Rsp<T> error(String msg) {
        Rsp<T> rsp = new Rsp<>();
        rsp.setCode(500);
        rsp.setMsg(msg);
        return rsp;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public T getData() {
        return data;
    }

    public T getExtra() {
        return extra;
    }

    public long getTotal() {
        return total;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public void setData(T data) {
        this.data = data;
    }

    public void setExtra(T extra) {
        this.extra = extra;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public boolean isSuccess() {
        return code == 0;
    }
}
