package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.exception.DrainRunRecordException;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TDrainRunRecordMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainRunRecordService;
import com.fc.v2.util.DrainSiteKeys;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 机组运行记录单Service业务层处理
 *
 * 口径：
 * 1. 录一条即落「待复核(status=0)」记号并写死删除标记 0——不靠库默认值（该列 DEFAULT NULL，
 *    漏写就是 NULL，台账只摆正常单时会当场失踪，现场明明录过却查无此单）；
 * 2. 台账摆全部未删单（待复核/已复核/已封存都摆），查询条件由调用方给，服务层不再替页面
 *    硬塞 status=0；分页也由调用方 startPage() 一处开，服务层不再自行分页（两处都开时
 *    里层 startPage(1,10) 会把页面的页号页长顶掉）；
 * 3. 受录泵站必须是在册在用档案：不存在/已停用不收，站点编号照档案回填，不信表单手填；
 * 4. 评定档照在档案阈值算，阈值允许只维护部分档位：某档上限为 NULL 视作该档未划线，
 *    顺次往下一档着，不许对 null 比较把整单录崩；
 * 5. 登记重载（一个机组一年一单）：时长为负/零或超全年小时数当场退回，同一机组当年
 *    已有过一张就不再开第二张；电耗核算费 = 运行时长 × 核算定额，与评定档一样由系统出，
 *    表单手填的一律覆盖，人不动手。
 *
 * 旧签名一律不动，新口径全走同名重载。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TDrainRunRecordServiceImpl extends ServiceImpl<TDrainRunRecordMapper, TDrainRunRecord> implements ITDrainRunRecordService {

    /** 记录单状态 0待复核 1已复核 2已封存，新录一律落待复核 */
    private static final int STATUS_WAIT = 0;

    /** 电耗核算定额（元/运行小时）：核算口径常量，口径要改只动这里 */
    private static final BigDecimal RUN_HOUR_PRICE = new BigDecimal("300");

    /** 运行时长量程上限（小时）：全年 24×365=8760，超过即数量级明显不对 */
    private static final BigDecimal MAX_RUN_HOURS = new BigDecimal("8760");

    @Autowired
    private TDrainStationMapper drainStationMapper;

    @Override
    public TDrainRunRecord selectTDrainRunRecordById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TDrainRunRecord>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper) {
        QueryWrapper<TDrainRunRecord> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TDrainRunRecord>) queryWrapper
                : new QueryWrapper<TDrainRunRecord>();
        // 台账只摆未删单；状态口径（待复核/已复核/已封存）交调用方按筛选条件决定，不在这里硬塞
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public List<TDrainRunRecord> selectTDrainRunRecordList(Wrapper<TDrainRunRecord> queryWrapper,
                                                           String roadName, String keyword) {
        QueryWrapper<TDrainRunRecord> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TDrainRunRecord>) queryWrapper
                : new QueryWrapper<TDrainRunRecord>();
        wrapper.eq("del_flag", 0);
        // 片区权限就压在这条 wrapper 里：别的片区的单 SQL 层面查不到，不是界面上藏
        if (roadName != null && !roadName.trim().isEmpty()) {
            List<TDrainStation> sites = this.drainStationMapper.selectList(
                    new QueryWrapper<TDrainStation>()
                            .eq("del_flag", 0)
                            .eq("road_name", roadName.trim()));
            List<String> siteNos = sites.stream()
                    .map(TDrainStation::getSiteNo)
                    .filter(s -> s != null && !s.trim().isEmpty())
                    .collect(Collectors.toList());
            if (siteNos.isEmpty()) {
                // 本片区名下没有在册站，一张单也查不到
                return new ArrayList<>();
            }
            wrapper.in("site_no", siteNos);
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
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTDrainRunRecord(TDrainRunRecord record) {
        if (record == null || record.getSiteId() == null) {
            return 0;
        }

        TDrainStation refArch = drainStationMapper.selectOne(new QueryWrapper<TDrainStation>()
                .eq("id", record.getSiteId()).eq("del_flag", 0));
        if (refArch == null) {
            // 受录泵站档案不存在：不收，避免落一条查无对档的孤单
            return 0;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            // 档案已停用的站不再录新单；停用要在档案里先改回来
            return 0;
        }
        record.setSiteNo(refArch.getSiteNo());
        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                    .eq("bill_no", record.getBillNo()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }
        record.setGradeLevel(gradeOf(refArch, record.getQty()));

        // 新录即待复核、正常单：显式落记号，不靠列默认值（status 列 DEFAULT NULL）
        record.setStatus(STATUS_WAIT);
        record.setDelFlag(0);
        record.setCreateBy(record.getBillNo());
        return this.baseMapper.insert(record);
    }

    @Override
    public int insertTDrainRunRecord(TDrainRunRecord record, String operator) {
        if (record == null) {
            throw new DrainRunRecordException("登记内容为空");
        }
        // 单号由人手填：空缺不收；重号不收（未删范围内）
        if (record.getBillNo() == null || record.getBillNo().trim().isEmpty()) {
            throw new DrainRunRecordException("运行单号不能为空，单号由人手填");
        }
        String billNo = record.getBillNo().trim();
        if (record.getSiteId() == null) {
            throw new DrainRunRecordException("受录泵站不能为空");
        }
        TDrainStation refArch = this.drainStationMapper.selectOne(new QueryWrapper<TDrainStation>()
                .eq("id", record.getSiteId()).eq("del_flag", 0));
        if (refArch == null) {
            throw new DrainRunRecordException("受录泵站不在册，开不了单");
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            throw new DrainRunRecordException("泵站[" + refArch.getSiteNo() + "]已停用，不再开新单");
        }
        // 运行时长：负、零、数量级明显不对（超全年小时数）当场退回，别放进流水
        BigDecimal qty = record.getQty();
        if (qty == null) {
            throw new DrainRunRecordException("机组实际运行时长不能为空");
        }
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DrainRunRecordException("机组实际运行时长须为正数，负值/零当场退回");
        }
        if (qty.compareTo(MAX_RUN_HOURS) > 0) {
            throw new DrainRunRecordException("机组实际运行时长超全年小时数[" + MAX_RUN_HOURS
                    + "]，数量级明显不对，当场退回");
        }
        Integer dupBill = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                .eq("bill_no", billNo).eq("del_flag", 0));
        if (dupBill != null && dupBill > 0) {
            throw new DrainRunRecordException("运行单号[" + billNo + "]已开过，不许重号");
        }
        // 一个机组一年一单：同一机组当年（按登记时刻归年）已有过一张就不再开第二张
        Calendar now = Calendar.getInstance();
        Calendar yearStart = Calendar.getInstance();
        yearStart.clear();
        yearStart.set(now.get(Calendar.YEAR), Calendar.JANUARY, 1, 0, 0, 0);
        Calendar nextYearStart = (Calendar) yearStart.clone();
        nextYearStart.add(Calendar.YEAR, 1);
        Integer sameYear = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                .eq("site_id", record.getSiteId())
                .eq("del_flag", 0)
                .ge("create_time", yearStart.getTime())
                .lt("create_time", nextYearStart.getTime()));
        if (sameYear != null && sameYear > 0) {
            throw new DrainRunRecordException("该机组" + now.get(Calendar.YEAR)
                    + "年已开过运行记录单，一个机组一年只许一单");
        }

        record.setBillNo(billNo);
        record.setSiteNo(refArch.getSiteNo());
        // 电耗核算费与评定档都由系统出：表单手填的一律覆盖，人不动手
        record.setFineAmt(qty.multiply(RUN_HOUR_PRICE).setScale(2, RoundingMode.HALF_UP));
        record.setGradeLevel(gradeOf(refArch, qty));
        record.setStatus(STATUS_WAIT);
        record.setDelFlag(0);
        record.setCreateBy(operator);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTDrainRunRecord(TDrainRunRecord record) {
        if (record == null || record.getId() == null) {
            return 0;
        }

        if (StringUtils.isNotEmpty(record.getBillNo())) {
            Integer dupCnt = this.baseMapper.selectCount(new QueryWrapper<TDrainRunRecord>()
                    .eq("bill_no", record.getBillNo()).ne("id", record.getId()).eq("del_flag", 0));
            if (dupCnt != null && dupCnt > 0) {
                return 0;
            }
        }

        record.setUpdateTime(new Date());
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
