package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.DrainArchiveException;
import com.fc.v2.mapper.auto.TDrainSiteTypeMapper;
import com.fc.v2.model.auto.TDrainSiteType;
import com.fc.v2.service.ITDrainSiteTypeService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 泵站类别标签树 Service业务层处理
 *
 * 口径：
 * 1. 类别是层级标签（parent_id=0 为根层，leaf_flag=1 才是末级）。挂标签只认
 *    {@link #requireLeafEnabled(String)} 这一个口：档案不存在/已删/是上层/已停用
 *    都当场报错，不许挂；
 * 2. 停用(1)只挡「新挂」这一下：先前挂到站、机组上的老标签原样留着，不回改不摘除。
 *    所以档案编辑时类别标签没变（表单照原值回显）就不重校，只有换标签才走末级校验；
 * 3. 类别代号未删范围内唯一——站档案与机组都拿代号挂标签，重号会挂错枝。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Service
public class TDrainSiteTypeServiceImpl implements ITDrainSiteTypeService {

    /** 末级标记 0上层 1末级 */
    private static final int UPPER = 0;
    private static final int LEAF = 1;

    /** 状态 0在用 1已停用 */
    private static final int IN_USE = 0;
    private static final int DISABLED = 1;

    /** 根层父代号 */
    private static final long ROOT_PARENT = 0L;

    @Resource
    private TDrainSiteTypeMapper drainSiteTypeMapper;

    @Override
    public List<TDrainSiteType> selectTDrainSiteTypeList(Wrapper<TDrainSiteType> queryWrapper) {
        QueryWrapper<TDrainSiteType> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TDrainSiteType>) queryWrapper
                : new QueryWrapper<TDrainSiteType>();
        wrapper.eq("del_flag", 0);
        wrapper.orderByAsc("parent_id", "order_num", "id");
        return this.drainSiteTypeMapper.selectList(wrapper);
    }

    @Override
    public TDrainSiteType selectTDrainSiteTypeById(Long id) {
        TDrainSiteType t = this.drainSiteTypeMapper.selectById(id);
        if (t == null || (t.getDelFlag() != null && t.getDelFlag() == 1)) {
            return null;
        }
        return t;
    }

    @Override
    public int insertTDrainSiteType(TDrainSiteType siteType) {
        validateBase(siteType);
        String code = siteType.getTypeCode().trim();
        ensureCodeNotDup(code, null);
        if (siteType.getParentId() == null) {
            siteType.setParentId(ROOT_PARENT);
        } else if (siteType.getParentId() != ROOT_PARENT) {
            ensureParentExists(siteType.getParentId());
        }
        siteType.setTypeCode(code);
        if (siteType.getLeafFlag() == null) {
            siteType.setLeafFlag(UPPER);
        }
        if (siteType.getStatus() == null) {
            siteType.setStatus(IN_USE);
        }
        if (siteType.getOrderNum() == null) {
            siteType.setOrderNum(0);
        }
        siteType.setDelFlag(0);
        return this.drainSiteTypeMapper.insert(siteType);
    }

    @Override
    public int updateTDrainSiteType(TDrainSiteType siteType) {
        if (siteType == null || siteType.getId() == null) {
            return 0;
        }
        TDrainSiteType exists = this.drainSiteTypeMapper.selectById(siteType.getId());
        if (exists == null || (exists.getDelFlag() != null && exists.getDelFlag() == 1)) {
            return 0;
        }
        if (siteType.getTypeCode() != null) {
            String code = siteType.getTypeCode().trim();
            if (code.isEmpty()) {
                throw new DrainArchiveException("类别代号不能为空");
            }
            ensureCodeNotDup(code, siteType.getId());
            siteType.setTypeCode(code);
        }
        if (siteType.getParentId() != null) {
            if (siteType.getParentId().equals(siteType.getId())) {
                throw new DrainArchiveException("上级类别不能挂到自己名下");
            }
            if (siteType.getParentId() != ROOT_PARENT) {
                ensureParentExists(siteType.getParentId());
            }
        }
        if (siteType.getLeafFlag() != null && siteType.getLeafFlag() != UPPER
                && siteType.getLeafFlag() != LEAF) {
            throw new DrainArchiveException("末级标记只许 0上层 或 1末级");
        }
        if (siteType.getStatus() != null && siteType.getStatus() != IN_USE
                && siteType.getStatus() != DISABLED) {
            throw new DrainArchiveException("类别状态只许 0在用 或 1已停用");
        }
        siteType.setUpdateTime(new Date());
        return this.drainSiteTypeMapper.updateById(siteType);
    }

    @Override
    public int changeStatus(Long id, Integer status) {
        if (id == null) {
            return 0;
        }
        if (status == null || (status != IN_USE && status != DISABLED)) {
            throw new DrainArchiveException("类别状态只许 0在用 或 1已停用");
        }
        TDrainSiteType exists = this.drainSiteTypeMapper.selectById(id);
        if (exists == null || (exists.getDelFlag() != null && exists.getDelFlag() == 1)) {
            return 0;
        }
        TDrainSiteType patch = new TDrainSiteType();
        patch.setId(id);
        patch.setStatus(status);
        patch.setUpdateTime(new Date());
        return this.drainSiteTypeMapper.updateById(patch);
    }

    @Override
    public TDrainSiteType requireLeafEnabled(String typeCode) {
        if (typeCode == null || typeCode.trim().isEmpty()) {
            throw new DrainArchiveException("泵站类别标签不能为空，只许挂末级类别");
        }
        String code = typeCode.trim();
        TDrainSiteType type = this.drainSiteTypeMapper.selectOne(new QueryWrapper<TDrainSiteType>()
                .eq("type_code", code).eq("del_flag", 0));
        if (type == null) {
            // 已删/从没建过的类别代号：不当成上层也不当成停用，直接判缺档
            throw new DrainArchiveException("泵站类别[" + code + "]不在册，不许挂标签");
        }
        if (type.getLeafFlag() == null || type.getLeafFlag() != LEAF) {
            throw new DrainArchiveException("[" + code + "]是上层类别，标签只许挂末级，挂到上层当场不收");
        }
        if (type.getStatus() != null && type.getStatus() == DISABLED) {
            throw new DrainArchiveException("类别[" + code + "]已停用，不再可选；先前挂过的站原样留着");
        }
        return type;
    }

    private void validateBase(TDrainSiteType siteType) {
        if (siteType == null) {
            throw new DrainArchiveException("类别内容为空");
        }
        if (siteType.getTypeCode() == null || siteType.getTypeCode().trim().isEmpty()) {
            throw new DrainArchiveException("类别代号不能为空");
        }
        if (siteType.getTypeName() == null || siteType.getTypeName().trim().isEmpty()) {
            throw new DrainArchiveException("类别名称不能为空");
        }
        if (siteType.getLeafFlag() != null && siteType.getLeafFlag() != UPPER
                && siteType.getLeafFlag() != LEAF) {
            throw new DrainArchiveException("末级标记只许 0上层 或 1末级");
        }
        if (siteType.getStatus() != null && siteType.getStatus() != IN_USE
                && siteType.getStatus() != DISABLED) {
            throw new DrainArchiveException("类别状态只许 0在用 或 1已停用");
        }
    }

    private void ensureCodeNotDup(String code, Long excludeId) {
        QueryWrapper<TDrainSiteType> w = new QueryWrapper<TDrainSiteType>()
                .eq("type_code", code).eq("del_flag", 0);
        if (excludeId != null) {
            w.ne("id", excludeId);
        }
        Integer dup = this.drainSiteTypeMapper.selectCount(w);
        if (dup != null && dup > 0) {
            throw new DrainArchiveException("类别代号[" + code + "]已存在，不许重号");
        }
    }

    private void ensureParentExists(Long parentId) {
        TDrainSiteType parent = selectTDrainSiteTypeById(parentId);
        if (parent == null) {
            throw new DrainArchiveException("上级类别不在册或已删除，挂不住");
        }
    }
}
