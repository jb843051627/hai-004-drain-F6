-- drain 泵站档案种子（id=0 在用、id=1 已停用，供档案联动与停用引用场景使用）
-- 三级阈值 1/5/10 与分档判定口径一致（临界取高一档、base=1）
INSERT INTO t_drain_station (id, site_no, site_name, site_type, road_name, th1_max, th2_max, th3_max, status, del_flag, create_by, create_time)
VALUES
 (0, 'PS00', '临湖一号泵站', '雨水', '城东片区', 1.00, 5.00, 10.00, 0, 0, 'seed', NOW()),
 (1, 'PS01', '老闸口泵站', '合流', '城西片区', 1.00, 5.00, 10.00, 1, 0, 'seed', NOW())
ON DUPLICATE KEY UPDATE site_name = VALUES(site_name), th1_max = VALUES(th1_max),
 th2_max = VALUES(th2_max), th3_max = VALUES(th3_max), status = VALUES(status), del_flag = 0;

-- 泵站类别种子：层级标签，挂站只许挂末级。
-- RAIN/COMBINED 是上层类别（挂到它们当场报错）；OLD-DIRECT 是停用的老类别（不再可选，
-- 先前挂过它的站原样留着）。站档案 site_type 里的中文老值（雨水/合流）即历史挂法，不追溯。
INSERT INTO t_drain_site_type (id, type_code, type_name, parent_code, status, del_flag, create_by, create_time)
VALUES
 (0, 'RAIN', '雨水', NULL, 0, 0, 'seed', NOW()),
 (1, 'RAIN-GRAVITY', '雨水-自排', 'RAIN', 0, 0, 'seed', NOW()),
 (2, 'RAIN-PUMP', '雨水-强排', 'RAIN', 0, 0, 'seed', NOW()),
 (3, 'COMBINED', '合流', NULL, 0, 0, 'seed', NOW()),
 (4, 'COMBINED-INTERCEPT', '合流-截流', 'COMBINED', 0, 0, 'seed', NOW()),
 (5, 'OLD-DIRECT', '老式直排', NULL, 1, 0, 'seed', NOW())
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), parent_code = VALUES(parent_code),
 status = VALUES(status), del_flag = 0;
