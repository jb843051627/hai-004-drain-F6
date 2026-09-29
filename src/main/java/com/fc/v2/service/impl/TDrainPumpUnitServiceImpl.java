package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.dataSources.DrainAreaScope;
import com.fc.v2.common.exception.DrainArchiveException;
import com.fc.v2.mapper.auto.TDrainPumpUnitMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainPumpUnit;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainPumpUnitService;
import com.fc.v2.service.ITDrainSiteTypeService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 泵站机组档案 Service业务层处理
 *
 * 口径：
 * 1. 机组必须挂在册未删泵站，站号照档案回填，不信表单手填；
 * 2. 计量点编码全局唯一：它是远传报文、台账精确检索、运行录单共用的外部锚点，
 *    逻辑删也不复用（查重不排除已删行），复用会把新机组的账算到旧读数上；
 * 3. 机组编号只在同站未删范围内唯一，跨站允许重号；
 * 4. 机组类别标签只许挂末级在用类别，挂上层/停用类别当场报错；
 * 5. 片区权限在查询 where 层经泵站档案裁：机组表本身没有片区列，走
 *    site_no IN (本片区泵站) 子查询，旁片区的机组一条都翻不到。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Service
public class TDrainPumpUnitServiceImpl implements ITDrainPumpUnitService {

    @Resource
    private TDrainPumpUnitMapper drainPumpUnitMapper;

    @Resource
    private TDrainStationMapper drainStationMapper;

    @Resource
    private ITDrainSiteTypeService drainSiteTypeService;

    @Resource
    private DrainAreaScope drainAreaScope;

    @Override
    public TDrainPumpUnit selectTDrainPumpUnitById(Long id) {
        TDrainPumpUnit unit = this.drainPumpUnitMapper.selectById(id);
        if (unit == null || (unit.getDelFlag() != null && unit.getDelFlag() == 1)) {
            return null;
        }
        TDrainStation station = stationByNo(unit.getSiteNo());
        if (station == null || !canReadRoad(station.getRoadName())) {
            return null;
        }
        return unit;
    }

    @Override
    public List<TDrainPumpUnit> selectTDrainPumpUnitList(Wrapper<TDrainPumpUnit> queryWrapper) {
        QueryWrapper<TDrainPumpUnit> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TDrainPumpUnit>) queryWrapper
                : new QueryWrapper<TDrainPumpUnit>();
        wrapper.eq("del_flag", 0);
        // 片区钉进 where：本表无片区列，经泵站档案子查询裁；授权空集 1=0 裁空
        DrainAreaScope.restrictByStationRoads(wrapper, "site_no", drainAreaScope.roadNamesOrNull());
        return this.drainPumpUnitMapper.selectList(wrapper);
    }

    @Override
    public int insertTDrainPumpUnit(TDrainPumpUnit unit) {
        validate(unit);
        TDrainStation station = this.drainStationMapper.selectById(unit.getSiteId());
        if (station == null || (station.getDelFlag() != null && station.getDelFlag() == 1)) {
            throw new DrainArchiveException("所属泵站档案不存在，机组挂不住");
        }
        drainAreaScope.assertRoad(station.getRoadName());

        String unitNo = unit.getUnitNo().trim();
        ensureUnitNoUnique(unitNo, station.getSiteNo(), null);
        String itemCode = unit.getItemCode().trim();
        ensureItemCodeUnique(itemCode, null);
        // 类别标签：末级在用才收，上层/停用当场报错
        drainSiteTypeService.requireLeafEnabled(unit.getSiteType().trim());

        unit.setUnitNo(unitNo);
        unit.setItemCode(itemCode);
        unit.setSiteType(unit.getSiteType().trim());
        unit.setSiteNo(station.getSiteNo());
        unit.setDelFlag(0);
        return this.drainPumpUnitMapper.insert(unit);
    }

    @Override
    public int updateTDrainPumpUnit(TDrainPumpUnit unit) {
        if (unit == null || unit.getId() == null) {
            return 0;
        }
        TDrainPumpUnit exists = this.drainPumpUnitMapper.selectById(unit.getId());
        if (exists == null || (exists.getDelFlag() != null && exists.getDelFlag() == 1)) {
            return 0;
        }
        TDrainStation station = stationByNo(exists.getSiteNo());
        if (station == null) {
            return 0;
        }
        drainAreaScope.assertRoad(station.getRoadName());

        // 不允许把机组挪到别的站：机组与站名下的计量锚点、运行单绑死，挪站会串账
        if (unit.getSiteId() != null && !unit.getSiteId().equals(station.getId())) {
            throw new DrainArchiveException("机组不许跨站挪动，要换站请另建机组档案");
        }
        if (unit.getUnitNo() != null) {
            String unitNo = unit.getUnitNo().trim();
            if (unitNo.isEmpty()) {
                throw new DrainArchiveException("机组编号不能为空");
            }
            ensureUnitNoUnique(unitNo, station.getSiteNo(), unit.getId());
            unit.setUnitNo(unitNo);
        }
        if (unit.getItemCode() != null) {
            String itemCode = unit.getItemCode().trim();
            if (itemCode.isEmpty()) {
                throw new DrainArchiveException("计量点编码不能为空");
            }
            ensureItemCodeUnique(itemCode, unit.getId());
            unit.setItemCode(itemCode);
        }
        // 只在换标签时校末级在用；旧标签照原值回显（含类别后停用的老挂）原样留
        if (unit.getSiteType() != null) {
            String newType = unit.getSiteType().trim();
            String oldType = exists.getSiteType() == null ? null : exists.getSiteType().trim();
            if (!newType.isEmpty() && !newType.equals(oldType)) {
                drainSiteTypeService.requireLeafEnabled(newType);
            }
            unit.setSiteType(newType.isEmpty() ? null : newType);
        }
        unit.setSiteNo(station.getSiteNo());
        unit.setUpdateTime(new Date());
        return this.drainPumpUnitMapper.updateById(unit);
    }

    private TDrainStation stationByNo(String siteNo) {
        if (siteNo == null || siteNo.trim().isEmpty()) {
            return null;
        }
        return this.drainStationMapper.selectOne(new QueryWrapper<TDrainStation>()
                .eq("site_no", siteNo).eq("del_flag", 0));
    }

    /** 片区读权限。 */
    private boolean canReadRoad(String roadName) {
        List<String> roads = drainAreaScope.roadNamesOrNull();
        if (roads == null) {
            return true;
        }
        return roadName != null && roads.contains(roadName.trim());
    }

    private void validate(TDrainPumpUnit unit) {
        if (unit == null) {
            throw new DrainArchiveException("机组内容为空");
        }
        if (unit.getSiteId() == null) {
            throw new DrainArchiveException("机组必须挂一座泵站");
        }
        if (unit.getUnitNo() == null || unit.getUnitNo().trim().isEmpty()) {
            throw new DrainArchiveException("机组编号不能为空");
        }
        if (unit.getUnitName() == null || unit.getUnitName().trim().isEmpty()) {
            throw new DrainArchiveException("机组名称不能为空");
        }
        if (unit.getItemCode() == null || unit.getItemCode().trim().isEmpty()) {
            throw new DrainArchiveException("计量点编码不能为空");
        }
        if (unit.getSiteType() == null || unit.getSiteType().trim().isEmpty()) {
            throw new DrainArchiveException("机组必须挂末级类别标签");
        }
    }

    /** 机组编号：同站未删范围内唯一。 */
    private void ensureUnitNoUnique(String unitNo, String siteNo, Long excludeId) {
        QueryWrapper<TDrainPumpUnit> w = new QueryWrapper<TDrainPumpUnit>()
                .eq("unit_no", unitNo).eq("site_no", siteNo).eq("del_flag", 0);
        if (excludeId != null) {
            w.ne("id", excludeId);
        }
        Integer dup = this.drainPumpUnitMapper.selectCount(w);
        if (dup != null && dup > 0) {
            throw new DrainArchiveException("本站已有机组编号[" + unitNo + "]，不许重号");
        }
    }

    /** 计量点编码：全局唯一，连已删行一起查，编码一旦用出永不复用。 */
    private void ensureItemCodeUnique(String itemCode, Long excludeId) {
        QueryWrapper<TDrainPumpUnit> w = new QueryWrapper<TDrainPumpUnit>()
                .eq("item_code", itemCode);
        if (excludeId != null) {
            w.ne("id", excludeId);
        }
        Integer dup = this.drainPumpUnitMapper.selectCount(w);
        if (dup != null && dup > 0) {
            throw new DrainArchiveException("计量点编码[" + itemCode + "]已被占用，编码全局唯一不许复用");
        }
    }
}
