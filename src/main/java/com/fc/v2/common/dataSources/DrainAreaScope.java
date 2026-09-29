package com.fc.v2.common.dataSources;

import java.util.ArrayList;
import java.util.List;

import com.fc.v2.mapper.auto.TDrainAreaGrantMapper;
import com.fc.v2.model.auto.TDrainAreaGrant;
import com.fc.v2.model.auto.TSysRole;
import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.service.ITSysRoleService;
import com.fc.v2.shiro.util.ShiroUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * drain 档案链片区数据权限。
 *
 * 口径：各片区的人只看得见本片区授权（t_drain_area_grant）名下的泵站、机组、运行单——
 * 由各服务在拼查询 where 时直接裁掉，旁片区在权限层就查不到，不是页面先藏起来；
 * admin 与挂「管理员」角色的登录人全片可见；无安全上下文（定时任务/单测）视作系统调用，
 * 不限片。授权底册里一行没有的普通登录人一片都看不到（不是默认放开）。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Component
public class DrainAreaScope {

    /** 全片可见的内置登录名 */
    public static final String BUILTIN_ADMIN = "admin";

    /** 全片可见的角色名（同 springbootv2.sql 种子「管理员」） */
    public static final String ADMIN_ROLE_NAME = "管理员";

    @Resource
    private TDrainAreaGrantMapper areaGrantMapper;

    @Resource
    private ITSysRoleService roleService;

    /** 当前登录人快照；测试里可继承覆写，不必起 Shiro 容器。 */
    public static class Login {
        private final String username;
        private final Long userId;

        public Login(String username, Long userId) {
            this.username = username;
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public Long getUserId() {
            return userId;
        }
    }

    /**
     * 取当前登录人；没有安全上下文（定时任务/未起容器的单测）返回 null，视作系统调用。
     * 包成 protected 方法：单测继承覆写即可造身份，不依赖 mockStatic。
     */
    protected Login currentLogin() {
        TSysUser user;
        try {
            user = ShiroUtils.getUser();
        } catch (RuntimeException e) {
            // 无安全管理器（定时任务/未起容器的单测）等：视作系统调用，不限片
            return null;
        }
        if (user == null || user.getUsername() == null) {
            return null;
        }
        return new Login(user.getUsername(), user.getId());
    }

    /** 当前调用是否全片可见（系统调用/admin/管理员角色）。 */
    public boolean isAllArea() {
        Login login = currentLogin();
        if (login == null) {
            return true;
        }
        if (BUILTIN_ADMIN.equalsIgnoreCase(login.getUsername())) {
            return true;
        }
        if (login.getUserId() == null || roleService == null) {
            return false;
        }
        List<TSysRole> roles = roleService.queryUserRole(login.getUserId());
        if (roles == null) {
            return false;
        }
        for (TSysRole role : roles) {
            if (role != null && ADMIN_ROLE_NAME.equals(role.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前登录人被授权的片区名。
     * 返回 null 表示不限片（全片可见/系统调用）；返回空表表示一片都没有，
     * 调用方必须把结果集裁空，不许把空当「没条件」放开。
     */
    public List<String> roadNamesOrNull() {
        if (isAllArea()) {
            return null;
        }
        Login login = currentLogin();
        if (login == null || areaGrantMapper == null) {
            return null;
        }
        List<TDrainAreaGrant> grants = areaGrantMapper.selectList(
                new QueryWrapper<TDrainAreaGrant>()
                        .eq("username", login.getUsername())
                        .eq("del_flag", 0));
        List<String> roads = new ArrayList<>();
        for (TDrainAreaGrant g : grants) {
            if (g.getRoadName() != null && !g.getRoadName().trim().isEmpty()
                    && !roads.contains(g.getRoadName().trim())) {
                roads.add(g.getRoadName().trim());
            }
        }
        return roads;
    }

    /**
     * 写操作过片区：普通片区人只许动自己片区名下的档案/单据。
     * roadName 为空（档案没挂片区）时，普通片区人也不许动——宁可挡下不留口子。
     */
    public void assertRoad(String roadName) {
        List<String> roads = roadNamesOrNull();
        if (roads == null) {
            return;
        }
        if (roadName == null || !roads.contains(roadName.trim())) {
            throw new com.fc.v2.common.exception.DrainArchiveException(
                    "越权：该泵站不在你的管辖片区，查不到也动不了");
        }
    }

    /**
     * 本表就有片区列（如 t_drain_station.road_name）：直接把授权片区钉进 where。
     * roads 为 null 表示全片可见不加条件；空表必须裁空——绝不能把空集合当「没条件」，
     * MyBatis-Plus 的 in(空集合) 是不加条件的，会把全表放出去。
     */
    public static <T> void restrictRoads(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<T> wrapper,
                                         String roadColumn, List<String> roads) {
        if (roads == null) {
            return;
        }
        if (roads.isEmpty()) {
            wrapper.apply("1 = 0");
        } else {
            wrapper.in(roadColumn, roads);
        }
    }

    /**
     * 本表只挂着泵站编号（机组/运行单）：走泵站档案子查询裁片区，片区列不在本表也照样钉死。
     * 参数用 {n} 占位绑定，不拼字符串值，避免片区名里带引号把条件撑破。
     */
    public static <T> void restrictByStationRoads(
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<T> wrapper,
            String siteNoColumn, List<String> roads) {
        if (roads == null) {
            return;
        }
        if (roads.isEmpty()) {
            wrapper.apply("1 = 0");
            return;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < roads.size(); i++) {
            if (i > 0) {
                placeholders.append(",");
            }
            placeholders.append("{").append(i).append("}");
        }
        wrapper.apply(siteNoColumn + " IN (SELECT site_no FROM t_drain_station "
                + "WHERE del_flag = 0 AND road_name IN (" + placeholders + "))",
                roads.toArray());
    }
}
