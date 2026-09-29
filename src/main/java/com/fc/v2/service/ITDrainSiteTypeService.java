package com.fc.v2.service;

import com.fc.v2.model.auto.TDrainSiteType;

import java.util.List;

/**
 * 泵站类别标签 Service接口
 *
 * @author fuce
 * @date 2026-09-29
 */
public interface ITDrainSiteTypeService {

    /** 建档/改档可挂的类别：启用且为末级（没有任何未删子类别） */
    List<TDrainSiteType> selectAttachableTypes();
}
