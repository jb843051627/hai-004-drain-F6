package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TDrainSiteTypeMapper;
import com.fc.v2.model.auto.TDrainSiteType;

/**
 * 泵站类别标签红线测试（内存库模拟表）：
 * 可挂类别只回「在用的末级」——上层类别、停用的老类别都不可选；
 * 层级看结构不看子类状态，子类停用了父类仍是上层。
 *
 * @author fuce
 * @date 2026-09-29
 */
class TDrainSiteTypeServiceImplTest {

    private TDrainSiteTypeServiceImpl service;
    private final Map<Long, TDrainSiteType> types = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        types.clear();

        TDrainSiteTypeMapper mapper = mock(TDrainSiteTypeMapper.class);
        when(mapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TDrainSiteType> w = inv.getArgument(0);
            Map<String, List<Occ>> p = parse(w.getSqlSegment(), w.getParamNameValuePairs());
            Integer delFlag = intEq(p, "del_flag");
            return types.values().stream()
                    .filter(t -> delFlag == null || delFlag.equals(t.getDelFlag()))
                    .collect(Collectors.toList());
        });

        service = new TDrainSiteTypeServiceImpl();
        ReflectionTestUtils.setField(service, "drainSiteTypeMapper", mapper);
    }

    private TDrainSiteType type(long id, String code, String name, String parent, int status) {
        TDrainSiteType t = new TDrainSiteType();
        t.setId(id);
        t.setTypeCode(code);
        t.setTypeName(name);
        t.setParentCode(parent);
        t.setStatus(status);
        t.setDelFlag(0);
        types.put(id, t);
        return t;
    }

    private List<String> attachableCodes() {
        return service.selectAttachableTypes().stream()
                .map(TDrainSiteType::getTypeCode)
                .collect(Collectors.toList());
    }

    // 1. 可挂的只回在用的末级：上层类别与停用的老类别都不在可选里
    @Test
    void attachable_onlyEnabledLeaves() {
        type(0, "RAIN", "雨水", null, 0);
        type(1, "RAIN-GRAVITY", "雨水-自排", "RAIN", 0);
        type(2, "RAIN-PUMP", "雨水-强排", "RAIN", 0);
        type(3, "OLD-DIRECT", "老式直排", null, 1);

        List<String> codes = attachableCodes();
        assertEquals(2, codes.size());
        assertTrue(codes.contains("RAIN-GRAVITY"));
        assertTrue(codes.contains("RAIN-PUMP"));
        assertTrue(!codes.contains("RAIN"), "上层类别不可选");
        assertTrue(!codes.contains("OLD-DIRECT"), "停用的老类别不可选");
    }

    // 2. 层级看结构不看子类状态：子类停用了，父类仍是上层，仍不可选
    @Test
    void attachable_parentWithDisabledChild_stillNotLeaf() {
        type(0, "GRAVE", "老旧类", null, 0);
        type(1, "GRAVE-OLD", "老旧类-停用子", "GRAVE", 1);

        List<String> codes = attachableCodes();
        assertTrue(codes.isEmpty(), "子类停用不等于父类变末级；停用子类本身也不可选");
    }

    // 3. 已删类别不占层级位置也不可选
    @Test
    void attachable_deletedType_ignored() {
        TDrainSiteType gone = type(0, "GONE", "已删类", null, 0);
        gone.setDelFlag(1);
        type(1, "RAIN-PUMP", "雨水-强排", "GONE", 0);

        List<String> codes = attachableCodes();
        assertEquals(1, codes.size());
        assertTrue(codes.contains("RAIN-PUMP"), "父类已删，它名下的末级照挂不误");
    }

    // ---- 参数抠取小工具（QueryWrapper 参数按 "?" 全局先后次序绑定） ----

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

    private static Integer intEq(Map<String, List<Occ>> p, String column) {
        List<Occ> occs = p.get(column);
        if (occs == null) {
            return null;
        }
        for (Occ o : occs) {
            if ("=".equals(o.op) && o.value instanceof Number) {
                return ((Number) o.value).intValue();
            }
        }
        return null;
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
