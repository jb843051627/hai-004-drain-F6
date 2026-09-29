package com.fc.v2.common.support;

import com.fc.v2.model.auto.TSysDepartment;
import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.service.ITSysDepartmentService;
import com.fc.v2.shiro.util.ShiroUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * drain 片区数据权限口径：当前登录人该看哪个片区。
 *
 * 部门名即片区名（管辖片区 road_name 对部门 deptName）：
 * - 管理端（admin）与未挂部门的账号不按片区收，全量可见；
 * - 挂了部门的片区账号只回本片区名，泵站/运行单两条台账都在 SQL 层面照它过滤——
 *   旁的片区一页都翻不到，这是权限层面就查不到，不是界面上先藏起来。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Component
public class DrainDataScope {

    @Autowired
    private ITSysDepartmentService sysDepartmentService;

    /**
     * 当前登录人的片区名；返回 null 表示不按片区收（管理端/未挂部门）。
     */
    public String currentRoadName() {
        TSysUser user = ShiroUtils.getUser();
        if (user == null || user.getId() == null) {
            // 拿不到登录态：@RequiresPermissions 会先拦，这里不按片区收
            return null;
        }
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            return null;
        }
        Long depId = user.getDepId();
        if (depId == null) {
            return null;
        }
        TSysDepartment dep = sysDepartmentService.selectTSysDepartmentById(depId);
        if (dep == null || dep.getDeptName() == null || dep.getDeptName().trim().isEmpty()) {
            return null;
        }
        return dep.getDeptName().trim();
    }
}
