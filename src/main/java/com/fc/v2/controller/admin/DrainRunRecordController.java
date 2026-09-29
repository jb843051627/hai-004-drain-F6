package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.service.ITDrainRunRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 机组运行记录单 Controller（batch-process 形状：写入口只有「登记」一处）
 *
 * 页面不替登记下结论：单号人手填，校验全在服务层办；
 * 电耗费与评定档由系统照档案算，页面提交不带这两列、也不预判，登记以返回为准。
 * 不开编辑、删除的口子——老单原样留存。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Api(value = "机组运行记录单")
@Controller
@RequestMapping("/DrainRunRecordController")
public class DrainRunRecordController extends BaseController {

    private final String prefix = "admin/drainRunRecord";

    @Autowired
    private ITDrainRunRecordService drainRunRecordService;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("drain:drainRunRecord:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "机组运行记录单集合查询", action = "list")
    @ApiOperation(value = "分页查询", notes = "台账只摆未删单；按片区数据权限在服务层 where 里裁，旁片区一页都翻不到；"
            + "按创建时刻+主键倒序，翻页排列稳")
    @GetMapping("/list")
    @RequiresPermissions("drain:drainRunRecord:list")
    @ResponseBody
    public ResultTable list(TDrainRunRecord record) {
        QueryWrapper<TDrainRunRecord> queryWrapper = new QueryWrapper<TDrainRunRecord>();
        // 状态/单号/站号/年度筛选由页面给；不筛选时待复核/已复核/已封存都摆（服务层只兜底未删+片区）
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        if (record.getBillNo() != null && !record.getBillNo().trim().isEmpty()) {
            queryWrapper.like("bill_no", record.getBillNo().trim());
        }
        if (record.getSiteNo() != null && !record.getSiteNo().trim().isEmpty()) {
            queryWrapper.eq("site_no", record.getSiteNo().trim());
        }
        if (record.getRunYear() != null) {
            queryWrapper.eq("run_year", record.getRunYear());
        }
        // 时刻外再钉主键收尾，同一时刻落两笔也不会翻页串行
        queryWrapper.orderByDesc("create_time", "id");
        startPage();
        com.github.pagehelper.PageInfo<TDrainRunRecord> page =
                new com.github.pagehelper.PageInfo<>(drainRunRecordService.selectTDrainRunRecordList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "机组运行记录单登记", action = "register")
    @ApiOperation(value = "登记", notes = "唯一写入口：单号人手填；一机组一年一单；受录站须在用；时长须为正且数量级正常；"
            + "电耗费、评定档由系统照档案核算，页面不动手，登记结果以返回为准")
    @PostMapping("/register")
    @RequiresPermissions("drain:drainRunRecord:add")
    @ResponseBody
    public AjaxResult register(TDrainRunRecord record) {
        // 这里不校验、不预判、不替登记下结论：原样交服务层，收不收、算出多少费与档全听服务层返回
        TDrainRunRecord saved = drainRunRecordService.registerTDrainRunRecord(
                record, v2Config.getDrainPowerPrice());
        return AjaxResult.success(200, "登记成功，电耗费与评定档以系统核算为准", saved);
    }
}
