package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TDrainSiteType;

import java.util.List;

/**
 * 泵站类别标签树 Service接口
 *
 * @author fuce
 * @date 2026-09-29
 */
public interface ITDrainSiteTypeService {

    /** 分类台账：只摆未删类别，条件由调用方给 */
    List<TDrainSiteType> selectTDrainSiteTypeList(Wrapper<TDrainSiteType> queryWrapper);

    /** 按主键查询（已删查不到） */
    TDrainSiteType selectTDrainSiteTypeById(Long id);

    /** 新增类别 */
    int insertTDrainSiteType(TDrainSiteType siteType);

    /** 编辑类别 */
    int updateTDrainSiteType(TDrainSiteType siteType);

    /** 改状态：0在用 1已停用（停用不动旧挂） */
    int changeStatus(Long id, Integer status);

    /**
     * 挂标签唯一入口：按代号取出「存在、未删、末级、在用」的类别，
     * 缺档/已删/挂到上层/已停用一律当场抛业务异常。先前挂过的老标签不回查这条。
     */
    TDrainSiteType requireLeafEnabled(String typeCode);
}
