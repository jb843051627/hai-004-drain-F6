package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TDrainStation;

import java.util.List;

/**
 * 在册泵站档案 Service接口
 *
 * @author fuce
 * @date 2026-09-26
 */
public interface ITDrainStationService {

    /** 按主键查询（已逻辑删除的查不到） */
    TDrainStation selectTDrainStationById(Long id);

    /** 台账：只摆未删档案，条件由调用方给 */
    List<TDrainStation> selectTDrainStationList(QueryWrapper<TDrainStation> queryWrapper);

    /**
     * 台账（片区权限 + 检索口径）：
     * roadName 非空时只摆该片区名下的站（权限层面就查不到旁的片区，不是界面上藏）；
     * keyword 非空时按站号/计量点编码精确命中（计量点编码取首段站号）。
     * 两个口径与分页计数同一条 wrapper，命中条数与浏览条数两边对得上。
     */
    List<TDrainStation> selectTDrainStationList(QueryWrapper<TDrainStation> queryWrapper,
                                                String roadName, String keyword);

    /** 新增在册档案 */
    int insertTDrainStation(TDrainStation station);

    /**
     * 新增在册档案（带类别层级校验）：类别非空时必须是在用的末级类别，
     * 挂到上层或停用的老类别当场报错；先前已挂的站不追溯。
     */
    int insertTDrainStation(TDrainStation station, String operator);

    /** 编辑档案（名称/阈值/状态都从这条口改） */
    int updateTDrainStation(TDrainStation station);

    /** 编辑档案（带类别层级校验，口径同 insert 重载） */
    int updateTDrainStation(TDrainStation station, String operator);

    /** 改档案状态：0在用 1已停用 */
    int changeStatus(Long id, Integer status);

    /** 开单可选的泵站：只摆未删且在用的档案，停用的站选不到 */
    List<TDrainStation> selectUsableStations();
}
