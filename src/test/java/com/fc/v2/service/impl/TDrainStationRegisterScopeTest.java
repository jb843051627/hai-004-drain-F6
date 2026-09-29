package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
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
import com.fc.v2.common.exception.DrainDispatchException;
import com.fc.v2.mapper.auto.TDrainSiteTypeMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainSiteType;
import com.fc.v2.model.auto.TDrainStation;

/**
 * 在册泵站档案新口径红线测试（内存库模拟表）：
 * 挂类别只许在用的末级（挂上层/挂停用老类别当场报错，先前挂过的站原样留着）、
 * 开单可选只摆在用站、台账按片区过滤、检索输站号或计量点编码都精确命中。
 * 旧签名行为不动：老 insert 不做类别校验。
 *
 * @author fuce
 * @date 2026-09-29
 */
class TDrainStationRegisterScopeTest {

    private TDrainStationServiceImpl service;
    private final Map<Long, TDrainStation> stations = new LinkedHashMap<>();
    private final Map<String, TDrainSiteType> types = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        stations.clear();
        types.clear();
        seq.set(100);

        TDrainStationMapper stationMapper = mock(TDrainStationMapper.class);
        TDrainSiteTypeMapper siteTypeMapper = mock(TDrainSiteTypeMapper.class);

        when(stationMapper.selectById(anyLong())).thenAnswer(inv -> stations.get(inv.getArgument(0)));

        when(stationMapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            return stations.values().stream()
                    .filter(s -> matches(s.getDelFlag(), p.get("del_flag")))
                    .filter(s -> matches(s.getStatus(), p.get("status")))
                    .filter(s -> matches(s.getSiteNo(), p.get("site_no")))
                    .filter(s -> matches(s.getSiteName(), p.get("site_name")))
                    .filter(s -> matches(s.getRoadName(), p.get("road_name")))
                    .collect(Collectors.toList());
        });

        when(stationMapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            return (int) stations.values().stream()
                    .filter(s -> matches(s.getDelFlag(), p.get("del_flag")))
                    .filter(s -> matches(s.getSiteNo(), p.get("site_no")))
                    .filter(s -> matches(s.getId(), p.get("id")))
                    .count();
        });

        when(stationMapper.insert(any())).thenAnswer(inv -> {
            TDrainStation s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(seq.incrementAndGet());
            }
            stations.put(s.getId(), s);
            return 1;
        });

        when(stationMapper.updateById(any())).thenAnswer(inv -> {
            TDrainStation patch = inv.getArgument(0);
            TDrainStation cur = stations.get(patch.getId());
            if (cur == null) {
                return 0;
            }
            if (patch.getSiteNo() != null) {
                cur.setSiteNo(patch.getSiteNo());
            }
            if (patch.getSiteName() != null) {
                cur.setSiteName(patch.getSiteName());
            }
            if (patch.getSiteType() != null) {
                cur.setSiteType(patch.getSiteType());
            }
            if (patch.getStatus() != null) {
                cur.setStatus(patch.getStatus());
            }
            if (patch.getUpdateBy() != null) {
                cur.setUpdateBy(patch.getUpdateBy());
            }
            return 1;
        });

        when(siteTypeMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainSiteType> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String code = strEq(p, "type_code");
            TDrainSiteType t = code == null ? null : types.get(code);
            return t != null && !Integer.valueOf(1).equals(t.getDelFlag()) ? t : null;
        });

        when(siteTypeMapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainSiteType> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String parent = strEq(p, "parent_code");
            return (int) types.values().stream()
                    .filter(t -> !Integer.valueOf(1).equals(t.getDelFlag()))
                    .filter(t -> parent == null || parent.equals(t.getParentCode()))
                    .count();
        });

        service = new TDrainStationServiceImpl();
        ReflectionTestUtils.setField(service, "drainStationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "drainSiteTypeMapper", siteTypeMapper);

        // 层级类别：RAIN 是上层（挂它当场报错）；RAIN-PUMP 是末级；OLD-DIRECT 停用不可选
        type("RAIN", "雨水", null, 0);
        type("RAIN-PUMP", "雨水-强排", "RAIN", 0);
        type("OLD-DIRECT", "老式直排", null, 1);
    }

    private TDrainSiteType type(String code, String name, String parent, int status) {
        TDrainSiteType t = new TDrainSiteType();
        t.setId((long) types.size());
        t.setTypeCode(code);
        t.setTypeName(name);
        t.setParentCode(parent);
        t.setStatus(status);
        t.setDelFlag(0);
        types.put(code, t);
        return t;
    }

    private TDrainStation stored(String siteNo, String roadName, int status) {
        TDrainStation s = new TDrainStation();
        s.setId(seq.incrementAndGet());
        s.setSiteNo(siteNo);
        s.setSiteName(siteNo + "号泵站");
        s.setRoadName(roadName);
        s.setStatus(status);
        s.setTh1Max(new BigDecimal("1"));
        s.setTh2Max(new BigDecimal("5"));
        s.setTh3Max(new BigDecimal("10"));
        s.setDelFlag(0);
        stations.put(s.getId(), s);
        return s;
    }

    private TDrainStation form(String siteNo, String siteType) {
        TDrainStation s = new TDrainStation();
        s.setSiteNo(siteNo);
        s.setSiteName(siteNo + "号泵站");
        s.setSiteType(siteType);
        return s;
    }

    // 1. 建档挂在用的末级类别：过
    @Test
    void insert_leafType_ok() {
        TDrainStation s = form("PS09", "RAIN-PUMP");
        assertEquals(1, service.insertTDrainStation(s, "zhangsan"));
        assertEquals("RAIN-PUMP", s.getSiteType());
        assertEquals("zhangsan", s.getCreateBy(), "登记人照操作者落");
    }

    // 2. 挂到上层类别当场报错，档案落不了库
    @Test
    void insert_parentType_rejected() {
        assertThrows(DrainDispatchException.class,
                () -> service.insertTDrainStation(form("PS09", "RAIN"), "zhangsan"));
        assertTrue(stations.isEmpty());
    }

    // 3. 停用的老类别不再可选
    @Test
    void insert_disabledType_rejected() {
        assertThrows(DrainDispatchException.class,
                () -> service.insertTDrainStation(form("PS09", "OLD-DIRECT"), "zhangsan"));
        assertTrue(stations.isEmpty());
    }

    // 4. 查无此类别
    @Test
    void insert_unknownType_rejected() {
        assertThrows(DrainDispatchException.class,
                () -> service.insertTDrainStation(form("PS09", "NOPE"), "zhangsan"));
        assertTrue(stations.isEmpty());
    }

    // 5. 不挂类别不拦
    @Test
    void insert_blankType_ok() {
        assertEquals(1, service.insertTDrainStation(form("PS09", null), "zhangsan"));
        assertEquals(1, service.insertTDrainStation(form("PS10", "  "), "zhangsan"));
    }

    // 6. 旧签名行为不动：老 insert 不做类别校验（签名保持不动，新口径全在重载上）
    @Test
    void legacyInsert_noTypeCheck() {
        assertEquals(1, service.insertTDrainStation(form("PS09", "RAIN")));
        assertEquals(1, stations.size());
    }

    // 7. 编辑换挂上层类别当场报错；换挂末级过
    @Test
    void update_changeType_checked() {
        TDrainStation s = stored("PS00", "城东片区", 0);

        TDrainStation bad = new TDrainStation();
        bad.setId(s.getId());
        bad.setSiteType("RAIN");
        assertThrows(DrainDispatchException.class, () -> service.updateTDrainStation(bad, "lisi"));

        TDrainStation good = new TDrainStation();
        good.setId(s.getId());
        good.setSiteType("RAIN-PUMP");
        assertEquals(1, service.updateTDrainStation(good, "lisi"));
        assertEquals("RAIN-PUMP", stations.get(s.getId()).getSiteType());
        assertEquals("lisi", stations.get(s.getId()).getUpdateBy());
    }

    // 8. 先前挂过的站原样留着：档案上挂着老类别，原样回传不拦；不动类别也不拦
    @Test
    void update_legacyTypeRoundTrip_notBlocked() {
        TDrainStation s = stored("PS00", "城东片区", 0);
        s.setSiteType("雨水"); // 历史挂法：中文老值，本就不在类别表里

        TDrainStation same = new TDrainStation();
        same.setId(s.getId());
        same.setSiteType("雨水");
        assertEquals(1, service.updateTDrainStation(same, "lisi"), "原样回传老类别不拦");

        TDrainStation noTouch = new TDrainStation();
        noTouch.setId(s.getId());
        noTouch.setSiteName("临湖一号泵站");
        assertEquals(1, service.updateTDrainStation(noTouch, "lisi"), "不动类别不拦");
        assertEquals("雨水", stations.get(s.getId()).getSiteType());
    }

    // 9. 开单可选只摆在用站：停用的选不到，老档案本身还在
    @Test
    void usableStations_onlyInUse() {
        stored("PS00", "城东片区", 0);
        stored("PS01", "城西片区", 1);

        List<TDrainStation> usable = service.selectUsableStations();
        assertEquals(1, usable.size());
        assertEquals("PS00", usable.get(0).getSiteNo());
        assertEquals(2, stations.size(), "停用站档案原样留着，只是开单选不到");
    }

    // 10. 台账按片区过滤：旁的片区权限层面就查不到
    @Test
    void list_scopedByRoadName() {
        stored("PS00", "城东片区", 0);
        stored("PS01", "城西片区", 0);
        stored("PS02", "城东片区", 0);

        List<TDrainStation> east = service.selectTDrainStationList(new QueryWrapper<>(), "城东片区", null);
        assertEquals(2, east.size());
        assertTrue(east.stream().allMatch(s -> "城东片区".equals(s.getRoadName())));

        List<TDrainStation> all = service.selectTDrainStationList(new QueryWrapper<>(), null, null);
        assertEquals(3, all.size(), "管理端（不按片区收）全量");
    }

    // 11. 检索输站号精确命中：PS00 命中 PS00，糊不到 PS001
    @Test
    void list_keywordSiteNo_exactHit() {
        stored("PS00", "城东片区", 0);
        stored("PS001", "城东片区", 0);

        List<TDrainStation> hit = service.selectTDrainStationList(new QueryWrapper<>(), null, "PS00");
        assertEquals(1, hit.size());
        assertEquals("PS00", hit.get(0).getSiteNo());
    }

    // 12. 检索输计量点编码也精确命中：首段即站号
    @Test
    void list_keywordItemCode_hitsStation() {
        stored("PS00", "城东片区", 0);
        stored("PS01", "城东片区", 0);

        List<TDrainStation> hit = service.selectTDrainStationList(new QueryWrapper<>(), null, "PS00-TEMP-01");
        assertEquals(1, hit.size());
        assertEquals("PS00", hit.get(0).getSiteNo());

        List<TDrainStation> hit2 = service.selectTDrainStationList(new QueryWrapper<>(), null, "PS01_HUMI_02");
        assertEquals(1, hit2.size());
        assertEquals("PS01", hit2.get(0).getSiteNo());
    }

    // 13. 检索与片区过滤叠加：命中的站不在本片区，一条都不回——两边条数天然对得上
    @Test
    void list_keywordAndScope_compose() {
        stored("PS00", "城东片区", 0);
        stored("PS01", "城西片区", 0);

        List<TDrainStation> hit = service.selectTDrainStationList(new QueryWrapper<>(), "城西片区", "PS00");
        assertTrue(hit.isEmpty(), "PS00 在城东，城西片区的人检索它也查不到");

        List<TDrainStation> hit2 = service.selectTDrainStationList(new QueryWrapper<>(), "城东片区", "PS00-TEMP-01");
        assertEquals(1, hit2.size());
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

    private static String strEq(Map<String, List<Occ>> p, String column) {
        List<Occ> occs = p.get(column);
        if (occs == null) {
            return null;
        }
        for (Occ o : occs) {
            if ("=".equals(o.op) && o.value != null) {
                return o.value.toString();
            }
        }
        return null;
    }

    /** 同列条件匹配：多个等值按 OR 集合看（检索候选就是 OR 关系），其余操作符逐个把守。 */
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

    private static final class Occ {
        final String op;
        final Object value;

        Occ(String op, Object value) {
            this.op = op;
            this.value = value;
        }
    }
}
