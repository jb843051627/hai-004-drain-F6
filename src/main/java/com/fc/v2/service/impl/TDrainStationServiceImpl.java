package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.DrainDispatchException;
import com.fc.v2.mapper.auto.TDrainSiteTypeMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainSiteType;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainStationService;
import com.fc.v2.util.DrainSiteKeys;
import org.springframework.stereotype.Service;

/**
 * 在册泵站档案 Service业务层处理
 *
 * 档案是巡检派单、运行录单两条链共用的底册：
 * 1. 台账只摆未删档案，状态(0在用/1已停用)照实摆，停用不是删除，巡检/录单两处仍要
 *    能认出「档案在、但停用了」这条坏行原由；
 * 2. 泵站编号唯一（未删范围内），巡检和录单都拿 site_no 对档，重号会让两条链对错站；
 * 3. 状态只许 0/1；改状态单独留口子，停用/复用都在档案里改，巡检派单与运行录单下一轮
 *    自然照新状态走，不必动条目本身；
 * 4. 三档阈值允许只维护部分档位：为 NULL 的档视作未划线（下游分档顺次往下着），
 *    但不许为负、不许二档比一档还低这类倒挂；
 * 5. 类别标签是层级的：新挂只许挂在用的末级类别，挂到上层或停用的老类别当场报错；
 *    先前挂过的站原样留着——编辑没动类别（或原样回传老类别）不追溯。
 *
 * 旧签名一律不动，新口径（类别校验、片区台账、检索命中）全走同名重载。
 *
 * @author fuce
 * @date 2026-09-26
 */
@Service
public class TDrainStationServiceImpl implements ITDrainStationService {

    /** 档案状态 0在用 1已停用 */
    private static final int IN_USE = 0;
    private static final int DISABLED = 1;

    @javax.annotation.Resource
    private TDrainStationMapper drainStationMapper;

    @javax.annotation.Resource
    private TDrainSiteTypeMapper drainSiteTypeMapper;

    @Override
    public TDrainStation selectTDrainStationById(Long id) {
        TDrainStation s = this.drainStationMapper.selectById(id);
        if (s == null || (s.getDelFlag() != null && s.getDelFlag() == 1)) {
            return null;
        }
        return s;
    }

    @Override
    public List<TDrainStation> selectTDrainStationList(QueryWrapper<TDrainStation> queryWrapper) {
        QueryWrapper<TDrainStation> wrapper = queryWrapper == null
                ? new QueryWrapper<TDrainStation>() : queryWrapper;
        wrapper.eq("del_flag", 0);
        return this.drainStationMapper.selectList(wrapper);
    }

    @Override
    public List<TDrainStation> selectTDrainStationList(QueryWrapper<TDrainStation> queryWrapper,
                                                       String roadName, String keyword) {
        QueryWrapper<TDrainStation> wrapper = queryWrapper == null
                ? new QueryWrapper<TDrainStation>() : queryWrapper;
        wrapper.eq("del_flag", 0);
        // 片区权限就压在这条 wrapper 里：别的片区的站 SQL 层面查不到，不是界面上藏
        if (roadName != null && !roadName.trim().isEmpty()) {
            wrapper.eq("road_name", roadName.trim());
        }
        // 检索与片区过滤同一条 wrapper：命中条数与按片区浏览的条数两边对得上
        List<String> candidates = DrainSiteKeys.siteNoCandidates(keyword);
        if (!candidates.isEmpty()) {
            wrapper.and(w -> {
                for (int i = 0; i < candidates.size(); i++) {
                    if (i > 0) {
                        w.or();
                    }
                    w.eq("site_no", candidates.get(i));
                }
            });
        }
        return this.drainStationMapper.selectList(wrapper);
    }

    @Override
    public int insertTDrainStation(TDrainStation station) {
        validate(station);
        String siteNo = station.getSiteNo().trim();
        Integer dup = this.drainStationMapper.selectCount(new QueryWrapper<TDrainStation>()
                .eq("site_no", siteNo).eq("del_flag", 0));
        if (dup != null && dup > 0) {
            throw new DrainDispatchException("泵站编号[" + siteNo + "]已在册，不许重复建档");
        }
        station.setSiteNo(siteNo);
        if (station.getStatus() == null) {
            station.setStatus(IN_USE);
        }
        station.setDelFlag(0);
        return this.drainStationMapper.insert(station);
    }

    @Override
    public int insertTDrainStation(TDrainStation station, String operator) {
        if (station != null && station.getSiteType() != null
                && !station.getSiteType().trim().isEmpty()) {
            // 新挂类别只许是在用的末级；挂到上层/停用的老类别当场报错
            checkSiteType(station.getSiteType().trim());
            station.setSiteType(station.getSiteType().trim());
        }
        if (station != null) {
            station.setCreateBy(operator);
        }
        return insertTDrainStation(station);
    }

    @Override
    public int updateTDrainStation(TDrainStation station) {
        if (station == null || station.getId() == null) {
            return 0;
        }
        TDrainStation exists = this.drainStationMapper.selectById(station.getId());
        if (exists == null || (exists.getDelFlag() != null && exists.getDelFlag() == 1)) {
            return 0;
        }
        if (station.getSiteNo() != null) {
            String siteNo = station.getSiteNo().trim();
            if (siteNo.isEmpty()) {
                throw new DrainDispatchException("泵站编号不能为空");
            }
            Integer dup = this.drainStationMapper.selectCount(new QueryWrapper<TDrainStation>()
                    .eq("site_no", siteNo).ne("id", station.getId()).eq("del_flag", 0));
            if (dup != null && dup > 0) {
                throw new DrainDispatchException("泵站编号[" + siteNo + "]与别的在册档案重号");
            }
            station.setSiteNo(siteNo);
        }
        if (station.getStatus() != null && station.getStatus() != IN_USE
                && station.getStatus() != DISABLED) {
            throw new DrainDispatchException("档案状态只许 0在用 或 1已停用");
        }
        validateThresholds(station);
        station.setUpdateTime(new Date());
        return this.drainStationMapper.updateById(station);
    }

    @Override
    public int updateTDrainStation(TDrainStation station, String operator) {
        if (station == null || station.getId() == null) {
            return 0;
        }
        if (station.getSiteType() != null && !station.getSiteType().trim().isEmpty()) {
            String newType = station.getSiteType().trim();
            TDrainStation cur = this.drainStationMapper.selectById(station.getId());
            String oldType = cur == null || cur.getSiteType() == null
                    ? null : cur.getSiteType().trim();
            // 换挂新类别才校验；原样回传档案上挂着的老类别不拦（先前挂过的站原样留着）
            if (oldType == null || !oldType.equals(newType)) {
                checkSiteType(newType);
            }
            station.setSiteType(newType);
        }
        station.setUpdateBy(operator);
        return updateTDrainStation(station);
    }

    @Override
    public int changeStatus(Long id, Integer status) {
        if (id == null) {
            return 0;
        }
        if (status == null || (status != IN_USE && status != DISABLED)) {
            throw new DrainDispatchException("档案状态只许 0在用 或 1已停用");
        }
        TDrainStation exists = this.drainStationMapper.selectById(id);
        if (exists == null || (exists.getDelFlag() != null && exists.getDelFlag() == 1)) {
            return 0;
        }
        TDrainStation patch = new TDrainStation();
        patch.setId(id);
        patch.setStatus(status);
        patch.setUpdateTime(new Date());
        return this.drainStationMapper.updateById(patch);
    }

    @Override
    public List<TDrainStation> selectUsableStations() {
        // 开单下拉只摆在用站：已停用的泵站选不到，名下的老单原样留存不受影响
        return this.drainStationMapper.selectList(new QueryWrapper<TDrainStation>()
                .eq("del_flag", 0)
                .eq("status", IN_USE)
                .orderByAsc("site_no", "id"));
    }

    /**
     * 挂类别校验：类别得存在、在用、且是末级（没有任何未删子类别还挂在它名下）。
     * 层级看结构不看子类状态——子类别停用了，上层的位置还在，仍不许挂。
     */
    private void checkSiteType(String typeCode) {
        TDrainSiteType type = this.drainSiteTypeMapper.selectOne(new QueryWrapper<TDrainSiteType>()
                .eq("type_code", typeCode).eq("del_flag", 0));
        if (type == null) {
            throw new DrainDispatchException("泵站类别[" + typeCode + "]不存在，挂不了");
        }
        if (type.getStatus() != null && type.getStatus() == DISABLED) {
            throw new DrainDispatchException("泵站类别[" + typeCode + "]已停用，不再可选");
        }
        Integer children = this.drainSiteTypeMapper.selectCount(new QueryWrapper<TDrainSiteType>()
                .eq("parent_code", typeCode).eq("del_flag", 0));
        if (children != null && children > 0) {
            throw new DrainDispatchException("泵站类别[" + typeCode + "]是上层类别，只许挂末级");
        }
    }

    /** 新增必填校验：编号、名称要有；状态合法；阈值不倒挂。 */
    private void validate(TDrainStation station) {
        if (station == null) {
            throw new DrainDispatchException("档案内容为空");
        }
        if (station.getSiteNo() == null || station.getSiteNo().trim().isEmpty()) {
            throw new DrainDispatchException("泵站编号不能为空");
        }
        if (station.getSiteName() == null || station.getSiteName().trim().isEmpty()) {
            throw new DrainDispatchException("泵站名称不能为空");
        }
        if (station.getStatus() != null && station.getStatus() != IN_USE
                && station.getStatus() != DISABLED) {
            throw new DrainDispatchException("档案状态只许 0在用 或 1已停用");
        }
        validateThresholds(station);
    }

    /** 阈值非负且不倒挂；为 NULL 的档位视作未划线，跳过不比。 */
    private void validateThresholds(TDrainStation s) {
        checkNonNegative(s.getTh1Max(), "一档上限");
        checkNonNegative(s.getTh2Max(), "二档上限");
        checkNonNegative(s.getTh3Max(), "三档上限");
        if (s.getTh1Max() != null && s.getTh2Max() != null
                && s.getTh2Max().compareTo(s.getTh1Max()) < 0) {
            throw new DrainDispatchException("二档上限不得低于一档上限");
        }
        if (s.getTh2Max() != null && s.getTh3Max() != null
                && s.getTh3Max().compareTo(s.getTh2Max()) < 0) {
            throw new DrainDispatchException("三档上限不得低于二档上限");
        }
        if (s.getTh1Max() != null && s.getTh3Max() != null
                && s.getTh3Max().compareTo(s.getTh1Max()) < 0) {
            throw new DrainDispatchException("三档上限不得低于一档上限");
        }
    }

    private void checkNonNegative(BigDecimal v, String label) {
        if (v != null && v.compareTo(BigDecimal.ZERO) < 0) {
            throw new DrainDispatchException(label + "不得为负");
        }
    }
}
