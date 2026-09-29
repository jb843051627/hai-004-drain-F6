-- drain 机组运行记录单种子（hai-004）
-- 演示三条口径：
--   * 一机组一年一单：机组 2001（PS00-1#）2026 年已有 YX-2026-2001，同年再登记当场退回；
--   * 停用站名下老单原样留存：机组 2011 属于已停用的 PS01，老单 YX-2025-2011 照摆照见，
--     但不许在 PS01 名下开新单；
--   * 电耗费/评定档是系统算出来的历史结果：0.5h × 0.80 = 0.40，阈值 1/5/10 下落一档。
-- 重复执行安全：ON DUPLICATE KEY UPDATE 只刷新非唯一列，不动唯一口径。

INSERT INTO t_drain_run_record
 (id, bill_no, unit_id, site_id, site_no, run_year, qty, fine_amt, grade_level, status, del_flag, create_by, create_time, remark)
VALUES
 (4001, 'YX-2026-2001', 2001, 0, 'PS00', 2026, 0.50, 0.40, 1, 1, 0, 'seed', NOW(), '2026汛季已登记：同机组同年第二张不收'),
 (4002, 'YX-2025-2011', 2011, 1, 'PS01', 2025, 2.00, 1.60, 2, 2, 0, 'seed', NOW(), '停用站名下老单：原样留存，只挡新开')
ON DUPLICATE KEY UPDATE qty = VALUES(qty), fine_amt = VALUES(fine_amt),
 grade_level = VALUES(grade_level), status = VALUES(status), del_flag = 0;
