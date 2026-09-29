package com.fc.v2.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 站端台账检索口径：输入站号或计量点编码都要能精确命中。
 *
 * 计量点编码按段认（- 或 _ 切开），首段即站号（如 PS00-TEMP-01 → PS00，
 * 与 MeterGaugeType 认表计段是同一把尺子）。所以检索候选 = 原样输入 + 首段，
 * 两边都按精确等值命中，不做模糊——输 PS00 不会把 PS001 糊进来。
 *
 * @author fuce
 * @date 2026-09-29
 */
public final class DrainSiteKeys {

    private DrainSiteKeys() {
    }

    /**
     * 检索词折成站号候选：原样一个；含分隔段时再补首段。去重保序。
     * 空输入回空串列表，调用方按「不带检索条件」处理。
     */
    public static List<String> siteNoCandidates(String keyword) {
        if (keyword == null) {
            return Collections.emptyList();
        }
        String kw = keyword.trim();
        if (kw.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(kw);
        String[] segs = kw.split("[-_]");
        if (segs.length > 1 && !segs[0].isEmpty()) {
            out.add(segs[0]);
        }
        return new ArrayList<>(out);
    }
}
