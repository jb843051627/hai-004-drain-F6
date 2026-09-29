package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TDrainPumpUnit;

import java.util.List;

/**
 * 泵站机组档案 Service接口
 *
 * @author fuce
 * @date 2026-09-29
 */
public interface ITDrainPumpUnitService {

    /** 按主键查询（已删/跨片区查不到） */
    TDrainPumpUnit selectTDrainPumpUnitById(Long id);

    /** 机组台账：条件由调用方给，片区权限服务层钉死 */
    List<TDrainPumpUnit> selectTDrainPumpUnitList(Wrapper<TDrainPumpUnit> queryWrapper);

    /** 新增机组 */
    int insertTDrainPumpUnit(TDrainPumpUnit unit);

    /** 编辑机组 */
    int updateTDrainPumpUnit(TDrainPumpUnit unit);
}
