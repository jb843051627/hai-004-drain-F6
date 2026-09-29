package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.conf.V2Config;
import com.fc.v2.common.dataSources.DrainAreaScope;
import com.fc.v2.common.exception.DrainArchiveException;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TDrainPumpUnitMapper;
import com.fc.v2.mapper.auto.TDrainRunRecordMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainPumpUnit;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainRunRecordService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 机组运行记录单Service业务层处理
 *
 * 口径：
 * 1. 写入口只有「登记」一处（{@link #registerTDrainRunRecord}），校验全在服务层，
 *    页面不替登记下结论、单号由人手填；登记成不成以返回为准；
 * 2. 一个机组一年一单：受录机组必填，运行年度取系统登记年（不信表单），
 *    同机组当年已有一张就不再开第二张，库唯一键 (unit_id, run_year) 并发兜底；
 * 3. 受录机组必须挂在册未删泵站，泵站在用（已停用不收，名下老单原样留存）；
 *    站号/站主键照机组所属站回填，表单手填的一律不信；
 * 4. 运行时长只许为正：负、零、数量级明显不对（超过全年小时数）当场退回，不进流水；
 * 5. 电耗费=时长×核算单价、评定档照档案三档上限折，两边都由系统出，
 *    表单就算带了 fine_amt/grade_level 也照系统值覆盖，人不动手；
 * 6. 台账只摆未删单，片区权限在 where 层经泵站档案裁：旁片区的单一页都翻不到。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TDrainRunRecordServiceImpl extends ServiceImpl<TDrainRunRecordMapper, TDrainRunRecord> implements ITDrainRunRecordService {

    /** 记录单状态 0待复核 1已复核 2已封存，新录一律落待复核 */
    private static final int STATUS_WAIT = 0;

    /** 档案/机组停用记号 */
    private static final int DISABLED = 1;

    /** 运行时长数量级上限（全年小时数，闰年）：超过就是数量级明显不对，当场退回 */
    private static final BigDecimal MAX_YEAR_HOURS = new BigDecimal("8784");

    /** 电耗费保留两位小数 */
    private static final int MONEY_SCALE = 2;

    /** 未配置核算单价时的兜底（元/运行小时） */
    private static final BigDecimal DEFAULT_POWER_PRICE = new BigDecimal("0.80");

    @Resource
    private TDrainStationMapper drainStationMapper;

    @Resource
    private TDrainPumpUnitMapper drainPumpUnitMapper;

    @Resource
    private DrainAreaScope drainAreaScope;

    @Resource
    private V2Config v2Config;

    @Override
    public TDrainRunRecord selectTDrainRunRecordById(Long id) {
        TDrainRunRecord r = this.baseMapper.selectOne(new QueryWrapper<TDrainRunRecord>()
                .eq("id", id)
                .eq("del_flag", 0));
        if (r == null) {
            return null;
        }
        TDrainStation station = stationByNo(r.getSiteNo());
        if (station == null || !canReadRoad(station.getRoadName())) {
            return null;
        }
        return r;
    }

    @Override
    public List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper) {
        QueryWrapper<TDrainRunRecord> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TDrainRunRecord>) queryWrapper
                : new QueryWrapper<TDrainRunRecord>();
        // 台账只摆未删单；状态口径（待复核/已复核/已封存）交调用方按筛选条件决定，不在这里硬塞
        wrapper.eq("del_flag", 0);
        // 片区钉进 where：本表无片区列，经泵站档案子查询裁；授权空集 1=0 裁空
        DrainAreaScope.restrictByStationRoads(wrapper, "site_no", drainAreaScope.roadNamesOrNull());
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTDrainRunRecord(TDrainRunRecord record) {
        registerTDrainRunRecord(record, currentPowerPrice());
        return 1;
    }

    @Override
    public TDrainRunRecord registerTDrainRunRecord(TDrainRunRecord record, BigDecimal powerPrice) {
        if (record == null) {
            throw new DrainArchiveException("登记内容为空");
        }
        // 单号人手填、必填，服务层校重号
        if (record.getBillNo() == null || record.getBillNo().trim().isEmpty()) {
            throw new DrainArchiveException("运行单号必须人手填写，不许空号");
        }
        String billNo = record.getBillNo().trim();
        Integer billDup = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                .eq("bill_no", billNo).eq("del_flag", 0));
        if (billDup != null && billDup > 0) {
            throw new DrainArchiveException("运行单号[" + billNo + "]已存在，单号人手填也不许重");
        }

        // 受录机组必填：一机组一年一单的锚点是机组，不是站
        if (record.getUnitId() == null) {
            throw new DrainArchiveException("请选择受录机组，一机组一年一单");
        }
        TDrainPumpUnit unit = this.drainPumpUnitMapper.selectById(record.getUnitId());
        if (unit == null || (unit.getDelFlag() != null && unit.getDelFlag() == 1)) {
            throw new DrainArchiveException("受录机组档案不存在或已删除，开不了单");
        }
        TDrainStation station = this.drainStationMapper.selectById(unit.getSiteId());
        if (station == null || (station.getDelFlag() != null && station.getDelFlag() == 1)) {
            throw new DrainArchiveException("机组所属泵站档案不存在，开不了单");
        }
        if (station.getStatus() != null && station.getStatus() == DISABLED) {
            // 停用泵站不许新单挂它；名下老单原样留存，只挡新开
            throw new DrainArchiveException("泵站[" + station.getSiteNo()
                    + "]已停用，新运行记录单不许再挂它；名下老单原样留存");
        }
        // 片区权限：旁片区的站，片区人开单时在服务层就被挡，不是页面藏
        drainAreaScope.assertRoad(station.getRoadName());

        // 运行时长：负、零、数量级离谱当场退回，别放进流水
        BigDecimal qty = record.getQty();
        if (qty == null) {
            throw new DrainArchiveException("机组实际运行时长必须填写");
        }
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DrainArchiveException("机组实际运行时长必须大于0，负值/零值当场退回");
        }
        if (qty.compareTo(MAX_YEAR_HOURS) > 0) {
            throw new DrainArchiveException("运行时长" + qty.toPlainString()
                    + "小时数量级明显不对（超过全年小时数），当场退回");
        }

        // 运行年度取系统登记年，表单给的不信
        int runYear = LocalDate.now().getYear();
        Integer yearDup = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                .eq("unit_id", unit.getId()).eq("run_year", runYear).eq("del_flag", 0));
        if (yearDup != null && yearDup > 0) {
            throw new DrainArchiveException("机组[" + unit.getUnitNo() + "]" + runYear
                    + "年已有一张运行记录单，一机组一年只许一张");
        }

        BigDecimal price = powerPrice != null ? powerPrice : DEFAULT_POWER_PRICE;
        // 系统核算两边都由系统出：先清空表单可能带来的值，再照系统口径算，人不动手
        record.setBillNo(billNo);
        record.setUnitId(unit.getId());
        record.setSiteId(station.getId());
        record.setSiteNo(station.getSiteNo());
        record.setRunYear(runYear);
        record.setFineAmt(qty.multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        record.setGradeLevel(gradeOf(station, qty));

        // 新录即待复核、正常单：显式落记号，不靠列默认值
        record.setStatus(STATUS_WAIT);
        record.setDelFlag(0);
        try {
            this.baseMapper.insert(record);
        } catch (DuplicateKeyException dupKey) {
            // 库唯一键兜底并发：同号或同机组同年两道闸，后到的这笔当场退回
            throw new DrainArchiveException("同号或同机组当年已有单据（并发撞单），本单退回");
        }
        return record;
    }

    @Override
    public int updateTDrainRunRecord(TDrainRunRecord record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        if (record.getBillNo() != null && !record.getBillNo().trim().isEmpty()) {
            String billNo = record.getBillNo().trim();
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                    .eq("bill_no", billNo).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                throw new DrainArchiveException("运行单号[" + billNo + "]与别的单重号");
            }
            record.setBillNo(billNo);
        }

        return this.baseMapper.update(record, new UpdateWrapper<TDrainRunRecord>()
                .eq("id", record.getId())
                .eq("del_flag", 0));
    }

    @Override
    public int deleteTDrainRunRecordByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        return this.baseMapper.deleteBatchIds(Arrays.asList(idArr));
    }

    @Override
    public int deleteTDrainRunRecordById(Long id) {
        return this.baseMapper.deleteById(id);
    }

    private BigDecimal currentPowerPrice() {
        if (v2Config != null && v2Config.getDrainPowerPrice() != null) {
            return v2Config.getDrainPowerPrice();
        }
        return DEFAULT_POWER_PRICE;
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

    /**
     * 照档案阈值定运行评定档（下发口径：临界取高一档，与档案种子 drain_seed.sql、
     * 判线 levelOf 同一把尺，base=1）：qty 落在 [上一档上限, 本档上限) 取本档，
     * 等于本档上限即压线，归高一档；某档上限为 NULL 表示该档未划线，顺次往下着；
     * 读数缺失或达到/超过全部已划线档归 4 档。
     */
    private int gradeOf(TDrainStation arch, BigDecimal qty) {
        if (qty == null) {
            return 0;
        }
        if (arch.getTh1Max() != null && qty.compareTo(arch.getTh1Max()) < 0) {
            return 1;
        }
        if (arch.getTh2Max() != null && qty.compareTo(arch.getTh2Max()) < 0) {
            return 2;
        }
        if (arch.getTh3Max() != null && qty.compareTo(arch.getTh3Max()) < 0) {
            return 3;
        }
        return 4;
    }
}
