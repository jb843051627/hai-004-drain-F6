package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
import com.fc.v2.common.exception.DrainArchiveException;
import com.fc.v2.mapper.auto.TDrainSiteTypeMapper;
import com.fc.v2.model.auto.TDrainSiteType;

/**
 * 泵站类别标签树红线测试：只许挂末级、停用类别不可新挂、代号唯一、状态只许 0/1。
 *
 * @author fuce
 * @date 2026-09-29
 */
class TDrainSiteTypeServiceImplTest {

    private TDrainSiteTypeServiceImpl service;
    private final Map<Long, TDrainSiteType> types = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        types.clear();
        seq.set(1000);
        TDrainSiteTypeMapper mapper = mock(TDrainSiteTypeMapper.class);

        when(mapper.selectById(anyLong())).thenAnswer(inv -> types.get(inv.getArgument(0)));
        when(mapper.selectList(any())).thenAnswer(inv -> types.values().stream()
                .filter(t -> !Integer.valueOf(1).equals(t.getDelFlag()))
                .collect(Collectors.toList()));
        when(mapper.selectOne(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainSiteType> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String code = strEq(p, "type_code");
            return code == null ? null : types.values().stream()
                    .filter(t -> !Integer.valueOf(1).equals(t.getDelFlag()))
                    .filter(t -> code.equals(t.getTypeCode()))
                    .findFirst().orElse(null);
        });
        when(mapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainSiteType> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            String code = strEq(p, "type_code");
            return (int) types.values().stream()
                    .filter(t -> !Integer.valueOf(1).equals(t.getDelFlag()))
                    .filter(t -> code != null && code.equals(t.getTypeCode()))
                    .count();
        });
        when(mapper.insert(any())).thenAnswer(inv -> {
            TDrainSiteType t = inv.getArgument(0);
            if (t.getId() == null) {
                t.setId(seq.incrementAndGet());
            }
            types.put(t.getId(), t);
            return 1;
        });
        when(mapper.updateById(any())).thenAnswer(inv -> {
            TDrainSiteType patch = inv.getArgument(0);
            TDrainSiteType cur = types.get(patch.getId());
            if (cur == null) {
                return 0;
            }
            if (patch.getTypeName() != null) {
                cur.setTypeName(patch.getTypeName());
            }
            if (patch.getStatus() != null) {
                cur.setStatus(patch.getStatus());
            }
            if (patch.getLeafFlag() != null) {
                cur.setLeafFlag(patch.getLeafFlag());
            }
            return 1;
        });

        service = new TDrainSiteTypeServiceImpl();
        ReflectionTestUtils.setField(service, "drainSiteTypeMapper", mapper);
    }

    private TDrainSiteType stored(String code, String name, int leaf, int status) {
        TDrainSiteType t = new TDrainSiteType();
        t.setId(seq.incrementAndGet());
        t.setTypeCode(code);
        t.setTypeName(name);
        t.setParentId(0L);
        t.setLeafFlag(leaf);
        t.setStatus(status);
        t.setOrderNum(0);
        t.setDelFlag(0);
        types.put(t.getId(), t);
        return t;
    }

    private TDrainSiteType form(String code, String name, Integer leaf) {
        TDrainSiteType t = new TDrainSiteType();
        t.setTypeCode(code);
        t.setTypeName(name);
        t.setLeafFlag(leaf);
        return t;
    }

    @Test
    void insert_defaultsUpperAndInUse() {
        assertEquals(1, service.insertTDrainSiteType(form("YS", "雨水", null)));
        TDrainSiteType t = service.selectTDrainSiteTypeList(new QueryWrapper<>()).get(0);
        assertEquals(0, t.getLeafFlag().intValue());
        assertEquals(0, t.getStatus().intValue());
    }

    @Test
    void insert_duplicateCode_rejected() {
        stored("YS", "雨水", 0, 0);
        assertThrows(DrainArchiveException.class, () -> service.insertTDrainSiteType(form("YS", "雨水2", 1)));
        assertEquals(1, types.size());
    }

    @Test
    void insert_missingCodeOrName_rejected() {
        assertThrows(DrainArchiveException.class,
                () -> service.insertTDrainSiteType(form("  ", "无名", 1)));
        TDrainSiteType noName = form("X1", "  ", 1);
        assertThrows(DrainArchiveException.class, () -> service.insertTDrainSiteType(noName));
        assertTrue(types.isEmpty());
    }

    // 末级在用：挂得上
    @Test
    void requireLeafEnabled_ok() {
        stored("YS_AX", "雨水-轴流", 1, 0);
        TDrainSiteType t = service.requireLeafEnabled("YS_AX");
        assertEquals("YS_AX", t.getTypeCode());
    }

    // 挂到上层当场报错
    @Test
    void requireLeafEnabled_upper_rejected() {
        stored("YS", "雨水", 0, 0);
        assertThrows(DrainArchiveException.class, () -> service.requireLeafEnabled("YS"));
    }

    // 停用老类别不再可选
    @Test
    void requireLeafEnabled_disabled_rejected() {
        stored("YS_LX", "雨水-离心", 1, 1);
        assertThrows(DrainArchiveException.class, () -> service.requireLeafEnabled("YS_LX"));
    }

    // 缺档/空代号当场报错
    @Test
    void requireLeafEnabled_missingOrBlank_rejected() {
        assertThrows(DrainArchiveException.class, () -> service.requireLeafEnabled("NOPE"));
        assertThrows(DrainArchiveException.class, () -> service.requireLeafEnabled("  "));
        assertThrows(DrainArchiveException.class, () -> service.requireLeafEnabled(null));
    }

    // 停用只挡新挂：停用后清单照摆（状态如实），旧挂由调用方原样留
    @Test
    void changeStatus_disable_shownInList() {
        TDrainSiteType t = stored("YS_LX", "雨水-离心", 1, 0);
        assertEquals(1, service.changeStatus(t.getId(), 1));
        assertEquals(1, types.get(t.getId()).getStatus().intValue());
        assertThrows(DrainArchiveException.class, () -> service.requireLeafEnabled("YS_LX"));
        List<TDrainSiteType> all = service.selectTDrainSiteTypeList(new QueryWrapper<>());
        assertEquals(1, all.size(), "停用类别在台账里照样摆");
    }

    @Test
    void changeStatus_illegalValue_rejected() {
        TDrainSiteType t = stored("YS_AX", "雨水-轴流", 1, 0);
        assertThrows(DrainArchiveException.class, () -> service.changeStatus(t.getId(), 9));
        assertEquals(0, types.get(t.getId()).getStatus().intValue());
    }

    @Test
    void selectById_deleted_returnsNull() {
        TDrainSiteType t = stored("YS_AX", "雨水-轴流", 1, 0);
        t.setDelFlag(1);
        assertNull(service.selectTDrainSiteTypeById(t.getId()));
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

    private static final class Occ {
        final String op;
        final Object value;

        Occ(String op, Object value) {
            this.op = op;
            this.value = value;
        }
    }
}
