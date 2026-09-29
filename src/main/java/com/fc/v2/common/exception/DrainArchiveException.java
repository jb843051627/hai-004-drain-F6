package com.fc.v2.common.exception;

/**
 * 泵站档案链业务规则异常：类别挂到上层、挂了已停用类别、停用站录新单、
 * 一机组同一年开第二张、运行时长填负/零/数量级离谱、单号手填撞号、
 * 跨片区翻看等红线一律抛此异常，由全局处理退回消息，单据不进流水。
 *
 * @author fuce
 * @date 2026-09-29
 */
public class DrainArchiveException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DrainArchiveException(String message) {
        super(message);
    }
}
