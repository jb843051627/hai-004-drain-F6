package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.dataSources.DrainAreaScope;
import com.fc.v2.common.exception.DrainDispatchException;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainSiteTypeService;
import com.fc.v2.service.ITDrainStationService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 在册泵站档案 Service业务层处理
 *
 * 档案是巡检派单、运行录单两条链共用的底册：
 * 1. 台账只摆未删档案，状态(0在用/1已停用)照实摆，停用不是删除；
 * 2. 泵站编号唯一（未删范围内），巡检和录单都拿 site_no 对档；
 * 3. 状态只许 0/1；改状态单独留口子，停用/复用都在档案里改，巡检派单与运行录单下一轮
 *    自然照新状态走，不必动条目本身；
 * 4. 三档阈值允许只维护部分档位：为 NULL 的档视作未划线（下游分档顺次往下着），
 *    但不许为负、不许二档比一档还低这类倒挂；
 * 5. 类别是层级标签，档案只许挂末级在用类别：新挂/换标签走类别服务当场校，
 *    挂到上层、挂已停用类别当场不收；编辑时标签没动（照原值回显）不重校，
 *       先前挂过的老标签——哪怕类别后来停用了——原样留着；
 * 6. 片区数据权限在查询 where 层裁：各片区的人只翻得到授权片区名下的档案，
 *    旁片区详情也查不到（不是页面藏）。
 *
 * @author fuce
 * @date 2026-09-26
 */
@Service
public class TDrainStationServiceImpl implements ITDrainStationService {

    /** 档案状态 0在用 1已停用 */
    private static final int IN_USE = 0;
    private static final int DISABLED = 1;

    @Resource
    private TDrainStationMapper drainStationMapper;

    @Resource
    private ITDrainSiteTypeService drainSiteTypeService;

    @Resource
    private DrainAreaScope drainAreaScope;

    @Override
    public TDrainStation selectTDrainStationById(Long id) {
        TDrainStation s = this.drainStationMapper.selectById(id);
        if (s == null || (s.getDelFlag() != null && s.getDelFlag() == 1)) {
            return null;
        }
        // 片区权限在查档这一层过：旁片区的档案对片区人就是「查不到」，不是查到了再藏
        if (!canReadRoad(s.getRoadName())) {
            return null;
        }
        return s;
    }

    @Override
    public List<TDrainStation> selectTDrainStationList(QueryWrapper<TDrainStation> queryWrapper) {
        QueryWrapper<TDrainStation> wrapper = queryWrapper == null
                ? new QueryWrapper<TDrainStation>() : queryWrapper;
        wrapper.eq("del_flag", 0);
        // 片区钉进 where：授权空集时 1=0 裁空，绝不能把「没授权」放成「没条件」
        DrainAreaScope.restrictRoads(wrapper, "road_name", drainAreaScope.roadNamesOrNull());
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
        // 片区人建档只许落在自己授权片区；管理员不限
        drainAreaScope.assertRoad(station.getRoadName());
        // 类别标签：填了就得是末级在用类别，挂上层/停用类别当场不收
        if (station.getSiteType() != null && !station.getSiteType().trim().isEmpty()) {
            drainSiteTypeService.requireLeafEnabled(station.getSiteType().trim());
            station.setSiteType(station.getSiteType().trim());
        }
        station.setSiteNo(siteNo);
        if (station.getStatus() == null) {
            station.setStatus(IN_USE);
        }
        station.setDelFlag(0);
        return this.drainStationMapper.insert(station);
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
        drainAreaScope.assertRoad(exists.getRoadName());
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
        // 换片区也要落在自己授权片区内；片区没动不重校
        if (station.getRoadName() != null
                && !station.getRoadName().trim().equals(trimOrNull(exists.getRoadName()))) {
            drainAreaScope.assertRoad(station.getRoadName().trim());
            station.setRoadName(station.getRoadName().trim());
        }
        // 只在「换标签」时校末级在用；标签照原值回显（含挂在已停用老类别上的旧挂）不重校，原样留
        if (station.getSiteType() != null) {
            String newType = station.getSiteType().trim();
            String oldType = trimOrNull(exists.getSiteType());
            if (!newType.isEmpty() && !newType.equals(oldType)) {
                drainSiteTypeService.requireLeafEnabled(newType);
            }
            station.setSiteType(newType.isEmpty() ? null : newType);
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
        drainAreaScope.assertRoad(exists.getRoadName());
        TDrainStation patch = new TDrainStation();
        patch.setId(id);
        patch.setStatus(status);
        patch.setUpdateTime(new Date());
        return this.drainStationMapper.updateById(patch);
    }

    /** 片区读权限：null=不限片；否则档案片区必须在授权集合内。 */
    private boolean canReadRoad(String roadName) {
        List<String> roads = drainAreaScope.roadNamesOrNull();
        if (roads == null) {
            return true;
        }
        return roadName != null && roads.contains(roadName.trim());
    }

    private static String trimOrNull(String v) {
        return v == null ? null : (v.trim().isEmpty() ? null : v.trim());
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
