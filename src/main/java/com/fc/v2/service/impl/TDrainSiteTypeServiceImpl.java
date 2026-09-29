package com.fc.v2.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TDrainSiteTypeMapper;
import com.fc.v2.model.auto.TDrainSiteType;
import com.fc.v2.service.ITDrainSiteTypeService;
import org.springframework.stereotype.Service;

/**
 * 泵站类别标签 Service业务层处理
 *
 * 可挂口径：启用(非停用)且末级。末级看结构不看状态——只要还有未删的子类别挂在
 * 它名下，它就是上层，哪怕子类别已停用；停用的老类别本身不再可选，但层级位置还在。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Service
public class TDrainSiteTypeServiceImpl implements ITDrainSiteTypeService {

    /** 类别状态 0启用 1停用 */
    private static final int DISABLED = 1;

    @javax.annotation.Resource
    private TDrainSiteTypeMapper drainSiteTypeMapper;

    @Override
    public List<TDrainSiteType> selectAttachableTypes() {
        List<TDrainSiteType> all = this.drainSiteTypeMapper.selectList(
                new QueryWrapper<TDrainSiteType>().eq("del_flag", 0));
        // 有未删子类别的编码收进父集：父集里的都是上层，不许挂
        Set<String> parents = new HashSet<>();
        for (TDrainSiteType t : all) {
            if (t.getParentCode() != null && !t.getParentCode().trim().isEmpty()) {
                parents.add(t.getParentCode().trim());
            }
        }
        return all.stream()
                .filter(t -> t.getStatus() == null || t.getStatus() != DISABLED)
                .filter(t -> t.getTypeCode() != null && !parents.contains(t.getTypeCode().trim()))
                .collect(Collectors.toList());
    }
}
