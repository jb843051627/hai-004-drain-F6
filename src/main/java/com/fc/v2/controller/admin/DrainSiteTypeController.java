package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainSiteType;
import com.fc.v2.service.ITDrainSiteTypeService;
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
 * 泵站类别标签树 Controller
 *
 * 标签是层级的，站档案/机组只许挂末级；停用类别不再可选，旧挂原样留。
 * 建档/编辑页挂标签走 /options 取「末级且在用」的可选清单，停用老类别压根不进下拉。
 *
 * @author fuce
 * @date 2026-09-29
 */
@Api(value = "泵站类别标签树")
@Controller
@RequestMapping("/drainSiteType")
public class DrainSiteTypeController extends BaseController {

    @Autowired
    private ITDrainSiteTypeService drainSiteTypeService;

    @Log(title = "泵站类别台账", action = "list")
    @ApiOperation(value = "类别树台账", notes = "未删类别照层级摆，在用/停用照实摆，按层级排序号")
    @GetMapping("/list")
    @RequiresPermissions("drainSiteType:list")
    @ResponseBody
    public ResultTable list(TDrainSiteType query) {
        QueryWrapper<TDrainSiteType> wrapper = new QueryWrapper<>();
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        }
        if (query.getParentId() != null) {
            wrapper.eq("parent_id", query.getParentId());
        }
        if (query.getTypeCode() != null && !query.getTypeCode().trim().isEmpty()) {
            wrapper.eq("type_code", query.getTypeCode().trim());
        }
        if (query.getTypeName() != null && !query.getTypeName().trim().isEmpty()) {
            wrapper.like("type_name", query.getTypeName().trim());
        }
        startPage();
        com.github.pagehelper.PageInfo<TDrainSiteType> page =
                new com.github.pagehelper.PageInfo<>(drainSiteTypeService.selectTDrainSiteTypeList(wrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "可选类别清单", action = "options")
    @ApiOperation(value = "可选类别清单", notes = "只摆末级且在用的类别；停用老类别不进清单")
    @GetMapping("/options")
    @RequiresPermissions("drainStation:list")
    @ResponseBody
    public AjaxResult options() {
        return AjaxResult.successData(200,
                drainSiteTypeService.selectTDrainSiteTypeList(new QueryWrapper<TDrainSiteType>()
                        .eq("leaf_flag", 1).eq("status", 0)));
    }

    @Log(title = "泵站类别详情", action = "detail")
    @GetMapping("/detail")
    @RequiresPermissions("drainSiteType:list")
    @ResponseBody
    public AjaxResult detail(@RequestParam Long id) {
        TDrainSiteType siteType = drainSiteTypeService.selectTDrainSiteTypeById(id);
        if (siteType == null) {
            return AjaxResult.error("类别不存在");
        }
        return AjaxResult.successData(200, siteType);
    }

    @Log(title = "泵站类别新增", action = "add")
    @PostMapping("/add")
    @RequiresPermissions("drainSiteType:add")
    @ResponseBody
    public AjaxResult add(TDrainSiteType siteType) {
        return toAjax(drainSiteTypeService.insertTDrainSiteType(siteType));
    }

    @Log(title = "泵站类别编辑", action = "edit")
    @PostMapping("/edit")
    @RequiresPermissions("drainSiteType:edit")
    @ResponseBody
    public AjaxResult edit(TDrainSiteType siteType) {
        return toAjax(drainSiteTypeService.updateTDrainSiteType(siteType));
    }

    @Log(title = "泵站类别改状态", action = "changeStatus")
    @ApiOperation(value = "改类别状态", notes = "0在用 1已停用；停用后不可再新挂，先前挂过的站原样留着")
    @PostMapping("/changeStatus")
    @RequiresPermissions("drainSiteType:edit")
    @ResponseBody
    public AjaxResult changeStatus(@RequestParam Long id, @RequestParam Integer status) {
        return toAjax(drainSiteTypeService.changeStatus(id, status));
    }
}
