package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.dataSources.DrainAreaScope;
import com.fc.v2.common.exception.DrainArchiveException;
import com.fc.v2.mapper.auto.TDrainPumpUnitMapper;
import com.fc.v2.mapper.auto.TDrainStationMapper;
import com.fc.v2.model.auto.TDrainPumpUnit;
import com.fc.v2.model.auto.TDrainSiteType;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainSiteTypeService;

/**
 * 泵站机组档案红线测试：挂在册站、计量点编码全局唯一（含已删不复用）、
 * 站内编号唯一、类别只许挂末级在用、片区写权限过片。
 *
 * @author fuce
 * @date 2026-09-29
 */
class TDrainPumpUnitServiceImplTest {

    private TDrainPumpUnitServiceImpl service;
    private final Map<Long, TDrainPumpUnit> units = new LinkedHashMap<>();
    private final Map<Long, TDrainStation> stations = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(2000);

    @BeforeEach
    void setUp() {
        units.clear();
        stations.clear();
        seq.set(2000);

        TDrainPumpUnitMapper unitMapper = mock(TDrainPumpUnitMapper.class);
        TDrainStationMapper stationMapper = mock(TDrainStationMapper.class);

        when(stationMapper.selectById(any())).thenAnswer(inv -> stations.get(((Number) inv.getArgument(0)).longValue()));
        when(stationMapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainStation> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String siteNo = strEq(p, "site_no");
            return siteNo == null ? null : stations.values().stream()
                    .filter(s -> siteNo.equals(s.getSiteNo())).findFirst().orElse(null);
        });
        when(unitMapper.selectById(any())).thenAnswer(inv -> units.get(((Number) inv.getArgument(0)).longValue()));
        when(unitMapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainPumpUnit> w = inv.getArgument(0);
            String seg = w.getSqlSegment();
            Map<String, List<Occ>> p = parse(seg, w.getParamNameValuePairs());
            String unitNo = strEq(p, "unit_no");
            String siteNo = strEq(p, "site_no");
            String itemCode = strEq(p, "item_code");
            Long neId = neLong(p, "id");
            // 计量点编码查重不带 del_flag：连已删行一起算，编码永不复用
            boolean includeDeleted = !seg.contains("del_flag");
            return (int) units.values().stream()
                    .filter(u -> includeDeleted || !Integer.valueOf(1).equals(u.getDelFlag()))
                    .filter(u -> unitNo == null || unitNo.equals(u.getUnitNo()))
                    .filter(u -> siteNo == null || siteNo.equals(u.getSiteNo()))
                    .filter(u -> itemCode == null || itemCode.equals(u.getItemCode()))
                    .filter(u -> neId == null || !neId.equals(u.getId()))
                    .count();
        });
        when(unitMapper.insert(any())).thenAnswer(inv -> {
            TDrainPumpUnit u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(seq.incrementAndGet());
            }
            units.put(u.getId(), u);
            return 1;
        });
        when(unitMapper.updateById(any())).thenAnswer(inv -> {
            TDrainPumpUnit patch = inv.getArgument(0);
            TDrainPumpUnit cur = units.get(patch.getId());
            if (cur == null) {
                return 0;
            }
            if (patch.getUnitNo() != null) {
                cur.setUnitNo(patch.getUnitNo());
            }
            if (patch.getItemCode() != null) {
                cur.setItemCode(patch.getItemCode());
            }
            if (patch.getSiteType() != null) {
                cur.setSiteType(patch.getSiteType());
            }
            return 1;
        });

        ITDrainSiteTypeService typeService = mock(ITDrainSiteTypeService.class);
        when(typeService.requireLeafEnabled(any())).thenAnswer(inv -> {
            TDrainSiteType t = new TDrainSiteType();
            t.setTypeCode(inv.getArgument(0));
            t.setLeafFlag(1);
            t.setStatus(0);
            return t;
        });

        service = new TDrainPumpUnitServiceImpl();
        ReflectionTestUtils.setField(service, "drainPumpUnitMapper", unitMapper);
        ReflectionTestUtils.setField(service, "drainStationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "drainSiteTypeService", typeService);
        ReflectionTestUtils.setField(service, "drainAreaScope", new DrainAreaScope());
        this.typeService = typeService;
    }

    private ITDrainSiteTypeService typeService;

    private TDrainStation station(long id, String siteNo, int status, String road) {
        TDrainStation s = new TDrainStation();
        s.setId(id);
        s.setSiteNo(siteNo);
        s.setSiteName(siteNo + "号泵站");
        s.setStatus(status);
        s.setRoadName(road);
        s.setDelFlag(0);
        stations.put(id, s);
        return s;
    }

    private TDrainPumpUnit form(long siteId, String unitNo, String itemCode, String type) {
        TDrainPumpUnit u = new TDrainPumpUnit();
        u.setSiteId(siteId);
        u.setUnitNo(unitNo);
        u.setUnitName(unitNo + "号机组");
        u.setItemCode(itemCode);
        u.setSiteType(type);
        return u;
    }

    private TDrainPumpUnit stored(long id, String unitNo, long siteId, String siteNo, String itemCode) {
        TDrainPumpUnit u = new TDrainPumpUnit();
        u.setId(id);
        u.setUnitNo(unitNo);
        u.setUnitName(unitNo + "号机组");
        u.setSiteId(siteId);
        u.setSiteNo(siteNo);
        u.setItemCode(itemCode);
        u.setSiteType("YS_AX");
        u.setDelFlag(0);
        units.put(id, u);
        return u;
    }

    @Test
    void insert_ok_backfillsSiteNo() {
        station(0L, "PS00", 0, "城东片区");
        TDrainPumpUnit u = form(0L, "1#", "PS00-EM-01", "YS_AX");
        u.setSiteNo("HACKED"); // 表单手填站号，必须被档案覆盖
        assertEquals(1, service.insertTDrainPumpUnit(u));
        assertEquals("PS00", u.getSiteNo());
        assertEquals(0, u.getDelFlag().intValue());
    }

    @Test
    void insert_missingStation_rejected() {
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainPumpUnit(form(404L, "1#", "X-EM-01", "YS_AX")));
        assertTrue(units.isEmpty());
    }

    @Test
    void insert_requiredFields_rejected() {
        station(0L, "PS00", 0, "城东片区");
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainPumpUnit(form(0L, " ", "PS00-EM-01", "YS_AX")), "机组编号空");
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainPumpUnit(form(0L, "1#", " ", "YS_AX")), "计量点编码空");
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainPumpUnit(form(0L, "1#", "PS00-EM-01", " ")), "类别空");
        assertTrue(units.isEmpty());
    }

    @Test
    void insert_duplicateUnitNo_sameSiteRejected_otherSiteAllowed() {
        station(0L, "PS00", 0, "城东片区");
        station(2L, "PS02", 0, "城北片区");
        assertEquals(1, service.insertTDrainPumpUnit(form(0L, "1#", "PS00-EM-01", "YS_AX")));
        // 同站再来一台 1#（计量点编码不同也不行：编号站内唯一）
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainPumpUnit(form(0L, "1#", "PS00-EM-09", "YS_AX")));
        // 跨站允许同编号
        assertEquals(1, service.insertTDrainPumpUnit(form(2L, "1#", "PS02-EM-01", "YS_AX")));
        assertEquals(2, units.size());
    }

    @Test
    void insert_duplicateItemCode_evenDeleted_notReusable() {
        station(0L, "PS00", 0, "城东片区");
        TDrainPumpUnit dead = stored(2099L, "OLD", 0L, "PS00", "PS00-EM-88");
        dead.setDelFlag(1); // 已删机组的编码也不许复用
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainPumpUnit(form(0L, "9#", "PS00-EM-88", "YS_AX")));
    }

    @Test
    void insert_nonLeafOrDisabledType_rejected() {
        station(0L, "PS00", 0, "城东片区");
        when(typeService.requireLeafEnabled("YS")).thenThrow(
                new DrainArchiveException("[YS]是上层类别，标签只许挂末级"));
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainPumpUnit(form(0L, "1#", "PS00-EM-01", "YS")));
        assertTrue(units.isEmpty());
    }

    @Test
    void insert_crossArea_rejected() {
        station(1L, "PS01", 0, "城西片区");
        DrainAreaScope eastOnly = fixedScope("城东片区");
        ReflectionTestUtils.setField(service, "drainAreaScope", eastOnly);
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainPumpUnit(form(1L, "1#", "PS01-EM-01", "YS_AX")));
        assertTrue(units.isEmpty());
    }

    @Test
    void insert_inOwnArea_ok() {
        station(0L, "PS00", 0, "城东片区");
        ReflectionTestUtils.setField(service, "drainAreaScope", fixedScope("城东片区"));
        assertEquals(1, service.insertTDrainPumpUnit(form(0L, "1#", "PS00-EM-01", "YS_AX")));
    }

    @Test
    void update_crossSiteMove_rejected() {
        station(0L, "PS00", 0, "城东片区");
        station(2L, "PS02", 0, "城北片区");
        TDrainPumpUnit u = stored(2001L, "1#", 0L, "PS00", "PS00-EM-01");
        TDrainPumpUnit patch = new TDrainPumpUnit();
        patch.setId(u.getId());
        patch.setSiteId(2L); // 想挪到 PS02
        assertThrows(DrainArchiveException.class, () -> service.updateTDrainPumpUnit(patch));
        assertEquals("PS00", units.get(u.getId()).getSiteNo());
    }

    @Test
    void selectById_crossArea_returnsNull() {
        station(1L, "PS01", 1, "城西片区");
        TDrainPumpUnit u = stored(2011L, "1#", 1L, "PS01", "PS01-EM-01");
        ReflectionTestUtils.setField(service, "drainAreaScope", fixedScope("城东片区"));
        assertNull(service.selectTDrainPumpUnitById(u.getId()), "旁片区机组权限层查不到");
    }

    @Test
    void update_unchangedOldTag_notRevalidated() {
        station(0L, "PS00", 0, "城东片区");
        TDrainPumpUnit u = stored(2001L, "1#", 0L, "PS00", "PS00-EM-01");
        u.setSiteType("YS_LX"); // 老机组挂着后来停用的类别
        TDrainPumpUnit patch = new TDrainPumpUnit();
        patch.setId(u.getId());
        patch.setSiteType("YS_LX"); // 照原值回显，只改名
        patch.setUnitName("改名机组");
        assertEquals(1, service.updateTDrainPumpUnit(patch));
        assertEquals("YS_LX", units.get(u.getId()).getSiteType(), "老标签原样留");
    }

    /** 固定片区身份：roads=null 全片；给值即只授这些片。 */
    static DrainAreaScope fixedScope(String... roads) {
        final List<String> list = roads.length == 0 ? null : new ArrayList<>(Arrays.asList(roads));
        return new DrainAreaScope() {
            @Override
            public List<String> roadNamesOrNull() {
                return list == null ? null : new ArrayList<>(list);
            }
        };
    }

    // ---- QueryWrapper 参数抠取 ----

    private static Map<String, List<Occ>> parse(String seg, Map<String, Object> params) {
        Map<String, List<Occ>> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "(\\w+)\\s*(=|<>)\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(seg);
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
        return v == null ? null : v.toString();
    }

    private static Long neLong(Map<String, List<Occ>> p, String column) {
        Object v = first(p, column, "<>");
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
