package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.exception.DrainRunRecordException;
import com.fc.v2.mapper.auto.TDrainRunRecordMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.model.auto.TDrainStation;

/**
 * 机组运行记录单登记红线测试（内存库模拟表）：
 * 一个机组一年一单；时长负/零/超全年小时数当场退回；停用/缺档站开不了新单；
 * 单号人手填且不重；电耗费与评定档由系统照档案算出（表单手填的一律覆盖）；
 * 台账按片区过滤、检索输站号或计量点编码都精确命中。
 *
 * @author fuce
 * @date 2026-09-29
 */
class TDrainRunRecordRegisterScopeTest {

    private TDrainRunRecordServiceImpl service;
    private final Map<Long, TDrainRunRecord> records = new LinkedHashMap<>();
    private final Map<Long, TDrainStation> stations = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(5000);

    @BeforeEach
    void setUp() {
        records.clear();
        stations.clear();
        seq.set(5000);

        TDrainRunRecordMapper recordMapper = mock(TDrainRunRecordMapper.class);
        TDrainStationMapper stationMapper = mock(TDrainStationMapper.class);

        when(stationMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Object id = eqVal(p, "id");
            TDrainStation s = id == null ? null : stations.get(((Number) id).longValue());
            return s != null && !Integer.valueOf(1).equals(s.getDelFlag()) ? s : null;
        });

        when(stationMapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            return stations.values().stream()
                    .filter(s -> matches(s.getDelFlag(), p.get("del_flag")))
                    .filter(s -> matches(s.getRoadName(), p.get("road_name")))
                    .collect(Collectors.toList());
        });

        when(recordMapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            return (int) records.values().stream().filter(r -> recordMatches(r, p)).count();
        });

        when(recordMapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainRunRecord> w = inv.getArgument(0);
            String seg = w.getSqlSegment();
            Map<String, List<Occ>> p = parse(seg, w.getParamNameValuePairs());
            Map<String, List<Object>> ins = parseIns(seg, w.getParamNameValuePairs());
            return records.values().stream()
                    .filter(r -> recordMatches(r, p))
                    .filter(r -> inMatches(r.getSiteNo(), ins.get("site_no")))
                    .collect(Collectors.toList());
        });

        when(recordMapper.insert(any())).thenAnswer(inv -> {
            TDrainRunRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(seq.incrementAndGet());
            }
            if (r.getCreateTime() == null) {
                // 生产上 create_time 由自动填充落库，这里照实模拟
                r.setCreateTime(new Date());
            }
            records.put(r.getId(), r);
            return 1;
        });

        service = new TDrainRunRecordServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", recordMapper);
        ReflectionTestUtils.setField(service, "drainStationMapper", stationMapper);

        station(0L, "PS00", "城东片区", 0);
        station(1L, "PS01", "城西片区", 1);
        station(2L, "PS02", "城东片区", 0);
    }

    private TDrainStation station(long id, String siteNo, String roadName, int status) {
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

    private TDrainRunRecord form(String billNo, Integer siteId, String qty) {
        TDrainRunRecord r = new TDrainRunRecord();
        r.setBillNo(billNo);
        r.setSiteId(siteId);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    /** 预存一条别的来源已落库的记录，create_time 照实给（测一年一单要归年）。 */
    private TDrainRunRecord stored(String billNo, int siteId, String siteNo, Date createTime) {
        TDrainRunRecord r = new TDrainRunRecord();
        r.setId(seq.incrementAndGet());
        r.setBillNo(billNo);
        r.setSiteId(siteId);
        r.setSiteNo(siteNo);
        r.setQty(new BigDecimal("2"));
        r.setGradeLevel(2);
        r.setStatus(0);
        r.setDelFlag(0);
        r.setCreateTime(createTime);
        records.put(r.getId(), r);
        return r;
    }

    private static Date yearsAgo(int n) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.YEAR, -n);
        return c.getTime();
    }

    // 1. 登记成功：电耗费=时长×核算定额、评定档照阈值，两边都由系统出；待复核、登记人落账
    @Test
    void register_ok_systemComputes() {
        TDrainRunRecord r = form("YX001", 0, "2.5");
        assertEquals(1, service.insertTDrainRunRecord(r, "zhangsan"));

        assertEquals(new BigDecimal("750.00"), r.getFineAmt(), "电耗费=2.5小时×300元/小时，系统算");
        assertEquals(2, r.getGradeLevel().intValue(), "2.5 压着一档线以上、未到二档线，落二档");
        assertEquals("PS00", r.getSiteNo(), "站号照档案回填");
        assertEquals(0, r.getStatus().intValue());
        assertEquals(0, r.getDelFlag().intValue());
        assertEquals("zhangsan", r.getCreateBy());
    }

    // 2. 表单手填的电耗费/评定档一律被系统覆盖，人不动手
    @Test
    void register_formSuppliedValues_overridden() {
        TDrainRunRecord r = form("YX002", 0, "0.5");
        r.setFineAmt(new BigDecimal("99999.99"));
        r.setGradeLevel(9);

        assertEquals(1, service.insertTDrainRunRecord(r, "zhangsan"));
        assertEquals(new BigDecimal("150.00"), r.getFineAmt(), "手填的电耗费不作数，照系统核算");
        assertEquals(1, r.getGradeLevel().intValue(), "手填的评定档不作数，照系统折档");
    }

    // 3. 时长负、零、空缺当场退回，别放进流水
    @Test
    void register_badQty_rejected() {
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-N", 0, "-1"), "op"));
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-Z", 0, "0"), "op"));
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-E", 0, null), "op"));
        assertTrue(records.isEmpty(), "坏单一条都不许落库");
    }

    // 4. 数量级明显不对的当场退回：全年 8760 小时是量程顶
    @Test
    void register_qtyMagnitude_boundary() {
        assertEquals(1, service.insertTDrainRunRecord(form("YX-MAX", 0, "8760"), "op"),
                "8760 是量程顶，收");
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-OVER", 2, "8760.01"), "op"));
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-HUGE", 2, "99999"), "op"));
    }

    // 5. 单号由人手填：空缺、重号都当场退回
    @Test
    void register_billNo_requiredAndUnique() {
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("  ", 0, "1"), "op"));
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form(null, 0, "1"), "op"));

        assertEquals(1, service.insertTDrainRunRecord(form("YX-DUP", 2, "1"), "op"));
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-DUP", 2, "2"), "op"));
    }

    // 6. 停用站、缺档站开不了新单
    @Test
    void register_disabledOrMissingStation_rejected() {
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-S1", 1, "1"), "op"), "停用站不收新单");
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-S2", 404, "1"), "op"), "查无此档不收");
        assertTrue(records.isEmpty());
    }

    // 7. 一个机组一年一单：当年已有过一张就不再开第二张；去年的不挡今年
    @Test
    void register_onePerUnitPerYear() {
        assertEquals(1, service.insertTDrainRunRecord(form("YX-Y1", 0, "1"), "op"));
        assertThrows(DrainRunRecordException.class,
                () -> service.insertTDrainRunRecord(form("YX-Y2", 0, "2"), "op"),
                "同一机组当年已有过一张，第二张当场退回");
        assertEquals(1, records.size());

        stored("YX-OLD", 2, "PS02", yearsAgo(1));
        assertEquals(1, service.insertTDrainRunRecord(form("YX-Y3", 2, "1"), "op"),
                "去年的单不挡今年开单");
    }

    // 8. 台账按片区过滤：旁的片区权限层面就查不到
    @Test
    void list_scopedByRoadName() {
        stored("YX001", 0, "PS00", new Date());
        stored("YX002", 1, "PS01", new Date());
        stored("YX003", 2, "PS02", new Date());

        List<TDrainRunRecord> east = service.selectTDrainRunRecordList(
                new QueryWrapper<>(), "城东片区", null);
        assertEquals(2, east.size());
        assertTrue(east.stream().allMatch(r -> !"PS01".equals(r.getSiteNo())));

        List<TDrainRunRecord> all = service.selectTDrainRunRecordList(
                new QueryWrapper<>(), null, null);
        assertEquals(3, all.size(), "管理端（不按片区收）全量，停用站名下的老单也照实摆");

        List<TDrainRunRecord> none = service.selectTDrainRunRecordList(
                new QueryWrapper<>(), "无人片区", null);
        assertTrue(none.isEmpty(), "片区名下没有站，一张单也查不到");
    }

    // 9. 检索输站号或计量点编码都精确命中；与片区过滤叠加，两边条数对得上
    @Test
    void list_keyword_hitsAndComposesWithScope() {
        stored("YX001", 0, "PS00", new Date());
        stored("YX002", 1, "PS01", new Date());
        stored("YX003", 2, "PS02", new Date());

        List<TDrainRunRecord> byNo = service.selectTDrainRunRecordList(
                new QueryWrapper<>(), null, "PS00");
        assertEquals(1, byNo.size());
        assertEquals("YX001", byNo.get(0).getBillNo());

        List<TDrainRunRecord> byItem = service.selectTDrainRunRecordList(
                new QueryWrapper<>(), null, "PS02-TEMP-01");
        assertEquals(1, byItem.size());
        assertEquals("YX003", byItem.get(0).getBillNo());

        List<TDrainRunRecord> composed = service.selectTDrainRunRecordList(
                new QueryWrapper<>(), "城东片区", "PS01");
        assertTrue(composed.isEmpty(), "PS01 在城西，城东片区的人检索它也查不到");
    }

    // ---- 参数抠取与条件匹配小工具（QueryWrapper 参数按 "?" 全局先后次序绑定） ----

    private static Map<String, List<Occ>> parse(String seg, Map<String, Object> params) {
        Map<String, List<Occ>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "(\\w+)\\s*(=|<>|>=|<=|>|<|LIKE)\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(seg);
        while (m.find()) {
            out.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                    .add(new Occ(m.group(2), params.get(m.group(3))));
        }
        return out;
    }

    private static Map<String, List<Object>> parseIns(String seg, Map<String, Object> params) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(\\w+)\\s+IN\\s*\\(([^)]*)\\)").matcher(seg);
        while (m.find()) {
            List<Object> vals = new ArrayList<>();
            Matcher pm = Pattern.compile("#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}")
                    .matcher(m.group(2));
            while (pm.find()) {
                vals.add(params.get(pm.group(1)));
            }
            out.put(m.group(1), vals);
        }
        return out;
    }

    private static Object eqVal(Map<String, List<Occ>> p, String column) {
        List<Occ> occs = p.get(column);
        if (occs == null) {
            return null;
        }
        for (Occ o : occs) {
            if ("=".equals(o.op)) {
                return o.value;
            }
        }
        return null;
    }

    private boolean recordMatches(TDrainRunRecord r, Map<String, List<Occ>> p) {
        return matches(r.getBillNo(), p.get("bill_no"))
                && matches(r.getSiteNo(), p.get("site_no"))
                && matches(r.getSiteId(), p.get("site_id"))
                && matches(r.getStatus(), p.get("status"))
                && matches(r.getDelFlag(), p.get("del_flag"))
                && matches(r.getCreateTime(), p.get("create_time"));
    }

    /** 同列条件匹配：多个等值按 OR 集合看（检索候选就是 OR 关系），范围/否定逐个把守。 */
    private static boolean matches(Object value, List<Occ> occs) {
        if (occs == null || occs.isEmpty()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        List<Object> eqs = new ArrayList<>();
        for (Occ o : occs) {
            switch (o.op) {
                case "=":
                    eqs.add(o.value);
                    break;
                case "<>":
                    if (Objects.equals(value, o.value)) {
                        return false;
                    }
                    break;
                case "LIKE":
                    if (!value.toString().contains(o.value.toString().replace("%", ""))) {
                        return false;
                    }
                    break;
                case ">=":
                    if (compare(value, o.value) < 0) {
                        return false;
                    }
                    break;
                case "<=":
                    if (compare(value, o.value) > 0) {
                        return false;
                    }
                    break;
                case ">":
                    if (compare(value, o.value) <= 0) {
                        return false;
                    }
                    break;
                case "<":
                    if (compare(value, o.value) >= 0) {
                        return false;
                    }
                    break;
                default:
                    break;
            }
        }
        if (!eqs.isEmpty()) {
            for (Object e : eqs) {
                if (Objects.equals(value, e)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean inMatches(Object value, List<Object> inVals) {
        if (inVals == null) {
            return true;
        }
        if (value == null || inVals.isEmpty()) {
            return false;
        }
        for (Object v : inVals) {
            if (Objects.equals(value, v)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a instanceof Comparable && a.getClass().isInstance(b)) {
            return ((Comparable) a).compareTo(b);
        }
        throw new IllegalArgumentException("不可比: " + a + " vs " + b);
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
