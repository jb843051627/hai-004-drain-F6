package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TDrainRunRecord;

import java.util.List;

/**
 * 机组运行记录单 Service接口
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface ITDrainRunRecordService {

    /** 按主键查询（跨片区/已删查不到） */
    TDrainRunRecord selectTDrainRunRecordById(Long id);

    /** 按条件查询列表（分页由调用方统一处理，片区权限服务层钉死） */
    List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper);

    /**
     * 新增（旧签名保留）。
     * 口径已与 {@link #registerTDrainRunRecord(TDrainRunRecord, java.math.BigDecimal)} 并轨：
     * 走登记全量校验，系统核算费与评定档，人不动手。
     */
    int insertTDrainRunRecord(TDrainRunRecord record);

    /**
     * 登记唯一业务入口（服务层校验）：单号人手填、一机组一年一单、停用站不收、
     * 时长非法当场退回；电耗费照 powerPrice（元/运行小时）由系统核算，
     * 评定档照档案三档上限由系统折，表单给的这两列一律不信。
     * 返回落库后的记录单（系统算出的费与档以返回为准）。
     */
    TDrainRunRecord registerTDrainRunRecord(TDrainRunRecord record, java.math.BigDecimal powerPrice);

    /** 修改 */
    int updateTDrainRunRecord(TDrainRunRecord record);

    /** 批量删除 */
    int deleteTDrainRunRecordByIds(String ids);

    /** 按主键删除 */
    int deleteTDrainRunRecordById(Long id);
}
