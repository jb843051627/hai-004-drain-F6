package com.fc.v2.common.exception;

/**
 * 机组运行记录单登记业务规则异常：时长为负/零/数量级明显不对、同一机组当年重单、
 * 停用/缺档站开新单、单号空缺或重号等红线一律抛此异常，由全局处理退回消息，
 * 坏单当场退回，不放进流水。
 *
 * @author fuce
 * @date 2026-09-29
 */
public class DrainRunRecordException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DrainRunRecordException(String message) {
        super(message);
    }
}
