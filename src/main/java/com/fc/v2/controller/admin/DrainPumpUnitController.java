package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainPumpUnit;
import com.fc.v2.service.ITDrainPumpUnitService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 泵站机组档案 Controller
 *
 * 机组挂在站下，带计量点编码与末级类别标签；台账按站/计量点编码检索，
 * 片区数据权限在服务层 where 里裁，旁片区机组翻不到。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Api(value = "泵站机组档案")
@Controller
@RequestMapping("/drainPumpUnit")
public class DrainPumpUnitController extends BaseController {

    @Autowired
    private ITDrainPumpUnitService drainPumpUnitService;

    @Log(title = "泵站机组台账", action = "list")
    @ApiOperation(value = "机组台账", notes = "按站号/计量点编码/机组编号筛；片区权限服务层裁；按站号、机组编号、主键稳排")
    @GetMapping("/list")
    @RequiresPermissions("drainPumpUnit:list")
    @ResponseBody
    public ResultTable list(TDrainPumpUnit query,
                            @RequestParam(value = "itemCode", required = false) String itemCode) {
        QueryWrapper<TDrainPumpUnit> wrapper = new QueryWrapper<>();
        wrapper.eq("del_flag", 0);
        if (query.getSiteNo() != null && !query.getSiteNo().trim().isEmpty()) {
            wrapper.eq("site_no", query.getSiteNo().trim());
        }
        if (query.getUnitNo() != null && !query.getUnitNo().trim().isEmpty()) {
            wrapper.eq("unit_no", query.getUnitNo().trim());
        }
        if (query.getSiteType() != null && !query.getSiteType().trim().isEmpty()) {
            wrapper.eq("site_type", query.getSiteType().trim());
        }
        // 计量点编码精确命中（远传报文与台账对锚的就是它，不做模糊）
        if (itemCode != null && !itemCode.trim().isEmpty()) {
            wrapper.eq("item_code", itemCode.trim());
        }
        wrapper.orderByAsc("site_no", "unit_no", "id");
        startPage();
        com.github.pagehelper.PageInfo<TDrainPumpUnit> page =
                new com.github.pagehelper.PageInfo<>(drainPumpUnitService.selectTDrainPumpUnitList(wrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "泵站机组详情", action = "detail")
    @GetMapping("/detail")
    @RequiresPermissions("drainPumpUnit:list")
    @ResponseBody
    public AjaxResult detail(@RequestParam Long id) {
        TDrainPumpUnit unit = drainPumpUnitService.selectTDrainPumpUnitById(id);
        if (unit == null) {
            return AjaxResult.error("机组不存在或不在你的管辖片区");
        }
        return AjaxResult.successData(200, unit);
    }

    @Log(title = "泵站机组新增", action = "add")
    @ApiOperation(value = "新增机组", notes = "挂在册泵站；计量点编码全局唯一；类别只许挂末级在用；站号系统回填")
    @PostMapping("/add")
    @RequiresPermissions("drainPumpUnit:add")
    @ResponseBody
    public AjaxResult add(TDrainPumpUnit unit) {
        return toAjax(drainPumpUnitService.insertTDrainPumpUnit(unit));
    }

    @Log(title = "泵站机组编辑", action = "edit")
    @ApiOperation(value = "编辑机组", notes = "改编号/计量点编码/名称/类别；不许跨站挪动")
    @PostMapping("/edit")
    @RequiresPermissions("drainPumpUnit:edit")
    @ResponseBody
    public AjaxResult edit(TDrainPumpUnit unit) {
        return toAjax(drainPumpUnitService.updateTDrainPumpUnit(unit));
    }
}
