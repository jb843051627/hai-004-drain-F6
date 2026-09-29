package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.common.support.DrainDataScope;
import com.fc.v2.model.auto.TDrainStation;
import com.fc.v2.service.ITDrainSiteTypeService;
import com.fc.v2.service.ITDrainStationService;
import com.fc.v2.shiro.util.ShiroUtils;
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
 * 在册泵站档案 Controller
 *
 * 巡检派单与运行录单都拿这份档案对底：
 * 页面只管摆台账、照表单建档/改阈值/改状态；档案停用(1)后，巡检把指向它的条目认作
 * 坏行留账，运行录单不再收它的新单——状态改回来两条链下一轮自然恢复。
 *
 * 类别标签是层级的：建档/改档挂类别只许挂在用的末级（校验在服务层办），
 * 停用的老类别不再可选，先前挂过的站原样留着。
 *
 * 台账检索输站号或计量点编码都精确命中；各片区的人只看得见本片区名下的站
 * （权限层面就查不到，不是界面上藏）。
 *
 * @author fuce
 * @date 2026-09-26
 */
@Api(value = "在册泵站档案")
@Controller
@RequestMapping("/drainStation")
public class DrainStationController extends BaseController {

    @Autowired
    private ITDrainStationService drainStationService;

    @Autowired
    private ITDrainSiteTypeService drainSiteTypeService;

    @Autowired
    private DrainDataScope drainDataScope;

    @Log(title = "在册泵站档案台账", action = "list")
    @ApiOperation(value = "泵站档案台账", notes = "只摆未删档案，在用/停用照实摆；站号/计量点编码精确命中；"
            + "只回当前人片区名下的站；按编号、主键排序，翻页排列得稳")
    @GetMapping("/list")
    @RequiresPermissions("drainStation:list")
    @ResponseBody
    public ResultTable list(TDrainStation query, @RequestParam(required = false) String keyword) {
        QueryWrapper<TDrainStation> wrapper = new QueryWrapper<>();
        if (query.getStatus() != null) {
            wrapper.eq("status", query.getStatus());
        }
        if (query.getSiteNo() != null && !query.getSiteNo().trim().isEmpty()) {
            wrapper.eq("site_no", query.getSiteNo().trim());
        }
        if (query.getSiteName() != null && !query.getSiteName().trim().isEmpty()) {
            wrapper.like("site_name", query.getSiteName().trim());
        }
        wrapper.orderByAsc("site_no", "id");
        startPage();
        com.github.pagehelper.PageInfo<TDrainStation> page =
                new com.github.pagehelper.PageInfo<>(drainStationService.selectTDrainStationList(
                        wrapper, drainDataScope.currentRoadName(), keyword));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "泵站档案详情", action = "detail")
    @ApiOperation(value = "泵站档案详情", notes = "含状态与三档阈值")
    @GetMapping("/detail")
    @RequiresPermissions("drainStation:list")
    @ResponseBody
    public AjaxResult detail(@RequestParam Long id) {
        TDrainStation station = drainStationService.selectTDrainStationById(id);
        if (station == null) {
            return AjaxResult.error("泵站档案不存在");
        }
        return AjaxResult.successData(200, station);
    }

    @Log(title = "开单可选泵站", action = "options")
    @ApiOperation(value = "开单可选泵站", notes = "只摆在用的档案：已停用的泵站开单时选不到，名下的老单原样留存")
    @GetMapping("/options")
    @RequiresPermissions("drainStation:list")
    @ResponseBody
    public AjaxResult options() {
        return AjaxResult.successData(200, drainStationService.selectUsableStations());
    }

    @Log(title = "可挂泵站类别", action = "typeOptions")
    @ApiOperation(value = "可挂泵站类别", notes = "只回在用的末级类别：上层类别与停用的老类别不再可选")
    @GetMapping("/typeOptions")
    @RequiresPermissions("drainStation:list")
    @ResponseBody
    public AjaxResult typeOptions() {
        return AjaxResult.successData(200, drainSiteTypeService.selectAttachableTypes());
    }

    @Log(title = "泵站档案新增", action = "add")
    @ApiOperation(value = "新增在册档案", notes = "编号唯一；缺省落在用；阈值可只维护部分档位；"
            + "挂类别只许在用的末级，挂到上层或停用的老类别当场报错")
    @PostMapping("/add")
    @RequiresPermissions("drainStation:add")
    @ResponseBody
    public AjaxResult add(TDrainStation station) {
        return toAjax(drainStationService.insertTDrainStation(station, ShiroUtils.getLoginName()));
    }

    @Log(title = "泵站档案编辑", action = "edit")
    @ApiOperation(value = "编辑档案", notes = "名称/片区/阈值等修改；编号改动仍校重号；"
            + "换挂类别照末级口径校验，原样回传老类别不拦")
    @PostMapping("/edit")
    @RequiresPermissions("drainStation:edit")
    @ResponseBody
    public AjaxResult edit(TDrainStation station) {
        return toAjax(drainStationService.updateTDrainStation(station, ShiroUtils.getLoginName()));
    }

    @Log(title = "泵站档案改状态", action = "changeStatus")
    @ApiOperation(value = "改档案状态", notes = "0在用 1已停用；停用后巡检/录单两条链照新状态走，档案本身不删")
    @PostMapping("/changeStatus")
    @RequiresPermissions("drainStation:edit")
    @ResponseBody
    public AjaxResult changeStatus(@RequestParam Long id, @RequestParam Integer status) {
        return toAjax(drainStationService.changeStatus(id, status));
    }
}
