package com.fc.v2.controller.admin;

import java.util.HashMap;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.common.support.DrainDataScope;
import com.fc.v2.model.auto.TDrainRunRecord;
import com.fc.v2.service.ITDrainRunRecordService;
import com.fc.v2.shiro.util.ShiroUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 机组运行记录单 Controller
 *
 * 写入口只有「登记」一处：单号由人手填，时长/一年一单/停用站等校验全在服务层办，
 * 电耗费与评定档由系统算出随返回回话——页面别替登记下结论，登记以返回为准。
 * 不开修改、删除的口子。
 *
 * 台账检索输站号或计量点编码都精确命中；各片区的人只看得见本片区名下的单
 * （权限层面就查不到，不是界面上藏）。
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

    @Autowired
    private DrainDataScope drainDataScope;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("drain:drainRunRecord:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "机组运行记录单集合查询", action = "list")
    @ApiOperation(value = "分页查询", notes = "站号/计量点编码精确命中；只回当前人片区名下的单；按落库时刻倒序、主键兜底，翻页排列得稳")
    @GetMapping("/list")
    @RequiresPermissions("drain:drainRunRecord:list")
    @ResponseBody
    public ResultTable list(TDrainRunRecord record, @RequestParam(required = false) String keyword) {
        QueryWrapper<TDrainRunRecord> queryWrapper = new QueryWrapper<TDrainRunRecord>();
        // 状态/单号/站号筛选由页面给；不筛选时待复核/已复核/已封存都摆（服务层只兜底未删）
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        if (record.getBillNo() != null && !record.getBillNo().trim().isEmpty()) {
            queryWrapper.like("bill_no", record.getBillNo().trim());
        }
        if (record.getSiteNo() != null && !record.getSiteNo().trim().isEmpty()) {
            queryWrapper.eq("site_no", record.getSiteNo().trim());
        }
        queryWrapper.orderByDesc("create_time", "id");
        startPage();
        com.github.pagehelper.PageInfo<TDrainRunRecord> page =
                new com.github.pagehelper.PageInfo<TDrainRunRecord>(
                        drainRunRecordService.selectTDrainRunRecordList(
                                queryWrapper, drainDataScope.currentRoadName(), keyword));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "机组运行记录单登记", action = "add")
    @ApiOperation(value = "登记", notes = "唯一写入口：单号人手填；时长/一年一单/停用站校验在服务层；"
            + "电耗费与评定档由系统算出随返回回话，登记以返回为准")
    @PostMapping("/add")
    @RequiresPermissions("drain:drainRunRecord:add")
    @ResponseBody
    public AjaxResult add(TDrainRunRecord record) {
        drainRunRecordService.insertTDrainRunRecord(record, ShiroUtils.getLoginName());
        Map<String, Object> data = new HashMap<>();
        data.put("billNo", record.getBillNo());
        data.put("siteNo", record.getSiteNo());
        data.put("qty", record.getQty());
        data.put("fineAmt", record.getFineAmt());
        data.put("gradeLevel", record.getGradeLevel());
        return AjaxResult.success(200, "登记成功", data);
    }
}
