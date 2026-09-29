package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.common.dataSources.DrainAreaScope;
import com.fc.v2.common.exception.DrainArchiveException;
import com.fc.v2.mapper.auto.TDrainPumpUnitMapper;
import com.fc.v2.mapper.auto.TDrainRunRecordMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainPumpUnit;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.model.auto.TDrainStation;

/**
 * 机组运行记录单登记红线测试（内存库模拟表）：
 * 单号人手填、一机组一年一单、停用站不收、时长负/零/数量级离谱当场退回、
 * 电耗费与评定档系统算出（表单值被覆盖）、台账三态都摆、片区 where 裁片。
 *
 * @author fuce
 * @date 2026-09-29
 */
class TDrainRunRecordServiceImplTest {

    private TDrainRunRecordServiceImpl service;
    private final Map<Long, TDrainRunRecord> records = new LinkedHashMap<>();
    private final Map<Long, TDrainStation> stations = new LinkedHashMap<>();
    private final Map<Long, TDrainPumpUnit> units = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(5000);
    private final BigDecimal price = new BigDecimal("0.80");

    @BeforeEach
    void setUp() {
        records.clear();
        stations.clear();
        units.clear();
        seq.set(5000);

        TDrainRunRecordMapper recordMapper = mock(TDrainRunRecordMapper.class);
        TDrainStationMapper stationMapper = mock(TDrainStationMapper.class);
        TDrainPumpUnitMapper unitMapper = mock(TDrainPumpUnitMapper.class);

        when(stationMapper.selectById(any())).thenAnswer(inv -> stations.get(((Number) inv.getArgument(0)).longValue()));
        when(stationMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String siteNo = strEq(p, "site_no");
            return siteNo == null ? null : stations.values().stream()
                    .filter(s -> siteNo.equals(s.getSiteNo())).findFirst().orElse(null);
        });
        when(unitMapper.selectById(any())).thenAnswer(inv -> units.get(((Number) inv.getArgument(0)).longValue()));

        when(recordMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Object id = first(p, "id", "=");
            TDrainRunRecord r = id == null ? null : records.get(((Number) id).longValue());
            return r != null && !Integer.valueOf(1).equals(r.getDelFlag()) ? r : null;
        });

        when(recordMapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String billNo = strEq(p, "bill_no");
            Long unitId = eqLong(p, "unit_id");
            Integer runYear = intEq(p, "run_year");
            return (int) records.values().stream()
                    .filter(r -> !Integer.valueOf(1).equals(r.getDelFlag()))
                    .filter(r -> billNo == null || billNo.equals(r.getBillNo()))
                    .filter(r -> unitId == null || unitId.equals(r.getUnitId()))
                    .filter(r -> runYear == null || runYear.equals(r.getRunYear()))
                    .count();
        });

        when(recordMapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Integer status = intEq(p, "status");
            String billLike = strEq(p, "bill_no");
            String siteNo = strEq(p, "site_no");
            return records.values().stream()
                    .filter(r -> !Integer.valueOf(1).equals(r.getDelFlag()))
                    .filter(r -> status == null || status.equals(r.getStatus()))
                    .filter(r -> billLike == null || (r.getBillNo() != null
                            && r.getBillNo().contains(billLike.replace("%", ""))))
                    .filter(r -> siteNo == null || siteNo.equals(r.getSiteNo()))
                    .collect(Collectors.toList());
        });

        when(recordMapper.insert(any())).thenAnswer(inv -> {
            TDrainRunRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(seq.incrementAndGet());
            }
            records.put(r.getId(), r);
            return 1;
        });

        when(recordMapper.update(any(), any(UpdateWrapper.class))).thenAnswer(inv -> {
            TDrainRunRecord r = inv.getArgument(0);
            records.put(r.getId(), r);
            return 1;
        });

        service = new TDrainRunRecordServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        ReflectionTestUtils.setField(service, "drainStationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "drainPumpUnitMapper", unitMapper);
        // 无安全上下文：系统调用，不限片
        ReflectionTestUtils.setField(service, "drainAreaScope", new DrainAreaScope());
    }

    private TDrainStation station(long id, String siteNo, Integer status, String roadName) {
        TDrainStation s = new TDrainStation();
        s.setId(id);
        s.setSiteNo(siteNo);
        s.setSiteName(siteNo + "号泵站");
        s.setRoadName(roadName);
        s.setStatus(status);
        s.setTh1Max(new BigDecimal("1"));
        s.setTh2Max(new BigDecimal("5"));
        s.setTh3Max(new BigDecimal("10"));
        s.setDelFlag(0);
        stations.put(id, s);
        return s;
    }

    private TDrainPumpUnit unit(long id, String unitNo, long siteId, String siteNo) {
        TDrainPumpUnit u = new TDrainPumpUnit();
        u.setId(id);
        u.setUnitNo(unitNo);
        u.setUnitName(unitNo + "号机组");
        u.setSiteId(siteId);
        u.setSiteNo(siteNo);
        u.setItemCode(siteNo + "-EM-0" + (id % 10));
        u.setSiteType("YS_AX");
        u.setDelFlag(0);
        units.put(id, u);
        return u;
    }

    private TDrainRunRecord form(String billNo, Long unitId, String qty) {
        TDrainRunRecord r = new TDrainRunRecord();
        r.setBillNo(billNo);
        r.setUnitId(unitId);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    private TDrainRunRecord register(String billNo, long unitId, String qty) {
        return service.registerTDrainRunRecord(form(billNo, unitId, qty), price);
    }

    // 1. 登记一张：单号手填、系统回填站号/年度、落待复核，台账看得见
    @Test
    void register_ok_setsSystemFields() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");

        TDrainRunRecord saved = register("YX001", 2001L, "0.5");

        assertEquals(0, saved.getStatus().intValue(), "新登记即待复核");
        assertEquals(0, saved.getDelFlag().intValue());
        assertEquals("PS00", saved.getSiteNo(), "站号照机组所属站回填，不信表单");
        assertEquals(0L, saved.getSiteId().longValue());
        assertEquals(LocalDate.now().getYear(), saved.getRunYear().intValue(), "运行年度取系统年");
        assertEquals(1, service.selectTDrainRunRecordList(new QueryWrapper<>()).size());
    }

    // 2. 电耗费由系统照时长×单价核算；表单硬塞的值被覆盖
    @Test
    void register_fineAmtComputedBySystem_formValueOverwritten() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        TDrainRunRecord r = form("YX002", 2001L, "2");
        r.setFineAmt(new BigDecimal("9999.99")); // 人手想定的费，必须被系统覆盖
        r.setGradeLevel(1);                      // 人手想定的档，必须被系统覆盖

        TDrainRunRecord saved = service.registerTDrainRunRecord(r, price);

        assertEquals(new BigDecimal("1.60"), saved.getFineAmt(), "2h × 0.80 = 1.60，系统说了算");
        assertEquals(2, saved.getGradeLevel().intValue(), "2 落在二档，系统折档，人手值被覆盖");
    }

    // 3. 单号人手填：空号退回
    @Test
    void register_blankBillNo_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register("  ", 2001L, "0.5"));
        assertTrue(records.isEmpty());
    }

    // 4. 单号手填也不许重
    @Test
    void register_duplicateBillNo_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        register("YX-DUP", 2001L, "0.5");
        assertThrows(DrainArchiveException.class, () -> register("YX-DUP", 2002L, "0.5"));
        assertEquals(1, records.size());
    }

    // 5. 一机组一年一单：同机组当年第二张退回；跨年/跨机组不连坐
    @Test
    void register_oneUnitOneYear_secondRejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        register("YX-A", 2001L, "0.5");
        assertThrows(DrainArchiveException.class, () -> register("YX-B", 2001L, "0.6"),
                "同一机组当年已有一张，不开第二张");
        // 不同机组同年可以各开一张
        assertEquals("YX-C", register("YX-C", 2002L, "0.7").getBillNo());
        assertEquals(2, records.size());
    }

    // 6. 停用站不许挂新单；老单原样留存
    @Test
    void register_disabledStation_rejected_oldKept() {
        TDrainStation s = station(1L, "PS01", 1, "城西片区");
        unit(2011L, "1#", 1L, "PS01");
        // 名下老单
        TDrainRunRecord old = new TDrainRunRecord();
        old.setId(4002L);
        old.setBillNo("YX-OLD");
        old.setUnitId(2011L);
        old.setSiteNo("PS01");
        old.setRunYear(LocalDate.now().getYear() - 1);
        old.setQty(new BigDecimal("2"));
        old.setGradeLevel(2);
        old.setStatus(2);
        old.setDelFlag(0);
        records.put(4002L, old);

        assertThrows(DrainArchiveException.class, () -> register("YX-NEW", 2011L, "0.5"));
        assertEquals(1, records.size(), "新单不进流水，老单原样留");
        assertNotNull(service.selectTDrainRunRecordList(new QueryWrapper<>())
                .stream().filter(r -> "YX-OLD".equals(r.getBillNo())).findFirst().orElse(null));

        s.setStatus(0); // 档案改回在用，下一轮自然能开（老单是去年的，不撞今年一单）
        TDrainRunRecord reopened = service.registerTDrainRunRecord(form("YX-NEW", 2011L, "0.5"), price);
        assertNotNull(reopened.getId());
        assertEquals("PS01", reopened.getSiteNo());
    }

    // 7. 时长：负、零当场退回
    @Test
    void register_negativeOrZeroQty_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register("YX-N1", 2001L, "-1"));
        assertThrows(DrainArchiveException.class, () -> register("YX-N2", 2001L, "0"));
        assertThrows(DrainArchiveException.class, () -> register("YX-N3", 2001L, null));
        assertTrue(records.isEmpty());
    }

    // 8. 时长数量级明显不对（超过全年小时数）当场退回
    @Test
    void register_absurdMagnitudeQty_rejected() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        assertThrows(DrainArchiveException.class, () -> register("YX-BIG", 2001L, "99999"));
        assertTrue(records.isEmpty(), "离谱时长不进流水");
    }

    // 9. 机组/泵站不存在不收
    @Test
    void register_missingUnitOrStation_rejected() {
        assertThrows(DrainArchiveException.class, () -> register("YX-M1", 404L, "0.5"),
                "机组不存在不开单");
        station(0L, "PS00", 0, "城东片区");
        TDrainPumpUnit ghost = unit(2099L, "9#", 0L, "PS00");
        stations.remove(0L); // 机组挂的站没了
        assertThrows(DrainArchiveException.class, () -> register("YX-M2", 2099L, "0.5"));
    }

    // 10. 缺机组锚点不收（一机组一年一单的锚点是机组不是站）
    @Test
    void register_withoutUnit_rejected() {
        TDrainRunRecord r = form("YX-NOUNIT", null, "0.5");
        assertThrows(DrainArchiveException.class,
                () -> service.registerTDrainRunRecord(r, price));
    }

    // 11. 台账三态都摆、不硬塞状态
    @Test
    void list_showsAllStatuses() {
        station(0L, "PS00", 0, "城东片区");
        unit(2001L, "1#", 0L, "PS00");
        unit(2002L, "2#", 0L, "PS00");
        unit(2003L, "3#", 0L, "PS00");
        TDrainRunRecord r1 = register("YX1", 2001L, "0.5");
        TDrainRunRecord r2 = register("YX2", 2002L, "0.5");
        TDrainRunRecord r3 = register("YX3", 2003L, "0.5");
        r1.setStatus(0);
        r2.setStatus(1);
        r3.setStatus(2);
        assertEquals(3, service.selectTDrainRunRecordList(new QueryWrapper<>()).size());
    }

    // ---- 参数抠取小工具（QueryWrapper 参数按 "?" 全局先后次序绑定） ----

    private static Map<String, List<Occ>> parse(String seg, Map<String, Object> params) {
        Map<String, List<Occ>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "(\\w+)\\s*(=|<>|LIKE)\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(seg);
        while (m.find()) {
            out.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                    .add(new Occ(m.group(2), params.get(m.group(3))));
        }
        return out;
    }

    private static Object first(Map<String, List<Occ>> parsed, String column, String op) {
        List<Occ> occs = parsed.get(column);
        if (occs == null) {
            return null;
        }
        for (Occ o : occs) {
            if (op == null || o.op.equals(op)) {
                return o.value;
            }
        }
        return null;
    }

    private static String strEq(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "=");
        if (v == null) {
            v = first(p, column, "LIKE");
        }
        return v == null ? null : v.toString();
    }

    private static Integer intEq(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "=");
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static Long eqLong(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "=");
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static final class Occ {
        final String op;
        final Object value;

        Occ(String op, Object value) {
            this.op = op;
            this.value = value;
        }
    }
}
