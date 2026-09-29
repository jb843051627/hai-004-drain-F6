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

    /** 按主键查询 */
    TDrainRunRecord selectTDrainRunRecordById(Long id);

    /** 按条件查询列表（分页由调用方统一处理） */
    List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper);

    /**
     * 台账（片区权限 + 检索口径）：
     * roadName 非空时只摆该片区名下泵站的运行单（权限层面查不到旁的片区）；
     * keyword 非空时按站号/计量点编码精确命中（计量点编码取首段站号）。
     * 两个口径与分页计数同一条 wrapper，命中条数与浏览条数两边对得上。
     */
    List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper,
                                                    String roadName, String keyword);

    /** 新增 */
    int insertTDrainRunRecord(TDrainRunRecord record);

    /**
     * 登记（一个机组一年一单）：单号人手填且不重；受录泵站须在册在用；
     * 运行时长须为正且不超全年小时数；同一机组当年已开过就不再开第二张；
     * 电耗核算费与运行评定档都由系统照档案口径算出（表单手填的一律覆盖）。
     * 红线违例抛 DrainRunRecordException，坏单当场退回，不放进流水。
     */
    int insertTDrainRunRecord(TDrainRunRecord record, String operator);

    /** 修改 */
    int updateTDrainRunRecord(TDrainRunRecord record);

    /** 批量删除 */
    int deleteTDrainRunRecordByIds(String ids);

    /** 按主键删除 */
    int deleteTDrainRunRecordById(Long id);
}
