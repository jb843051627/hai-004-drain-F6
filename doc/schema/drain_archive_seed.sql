-- drain 泵站档案链种子（类别层级标签 / 机组 / 片区授权）（hai-004）
-- 配合档案三条规矩：
--   * 类别是层级标签，只许挂末级：YS/HL 是上层，YS_AX/YS_LX/HL_HB 是末级；
--   * 停用老类别不再可选：YS_LX（雨水-离心）停用，先前挂过的站/机组原样留着；
--   * 老站 PS00/PS02 档案上的 site_type 仍是上层代号「雨水」——那是先前挂的老标签，
--     类别上层化/停用都不回改不摘除；新挂、换标签才必须挂末级在用类别。
-- 机组计量点编码全局唯一，与远传报文（PS00-TEMP-01 式编码同一段式）对锚。
-- 片区授权：duty_li 管城东片区（PS00）、duty_wang 管城西片区（停用站 PS01）；
-- admin 与「管理员」角色不走授权表，全片可见。
-- 重复执行安全：一律 ON DUPLICATE KEY UPDATE / 先按主键幂等。

-- 泵站类别标签树
INSERT INTO t_drain_site_type (id, type_code, type_name, parent_id, leaf_flag, status, order_num, del_flag, create_by, create_time, remark)
VALUES
 (100, 'YS',    '雨水',       0,   0, 0, 1, 0, 'seed', NOW(), '上层类别：只作归类，不许直接挂'),
 (101, 'HL',    '合流',       0,   0, 0, 2, 0, 'seed', NOW(), '上层类别：只作归类，不许直接挂'),
 (110, 'YS_AX', '雨水-轴流',  100, 1, 0, 1, 0, 'seed', NOW(), '末级在用：档案/机组挂这个'),
 (111, 'YS_LX', '雨水-离心',  100, 1, 1, 2, 0, 'seed', NOW(), '末级但已停用：不再可选，旧挂原样留'),
 (120, 'HL_HB', '合流-混截',  101, 1, 0, 1, 0, 'seed', NOW(), '末级在用：档案/机组挂这个')
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), parent_id = VALUES(parent_id),
 leaf_flag = VALUES(leaf_flag), status = VALUES(status), del_flag = 0;

-- 机组档案：PS00 两台（在用站，可登记运行单），PS01 一台（停用站，老机组/老单原样留、不许开新单），PS02 一台
INSERT INTO t_drain_pump_unit (id, unit_no, unit_name, site_id, site_no, item_code, site_type, del_flag, create_by, create_time, remark)
VALUES
 (2001, '1#', '临湖一号1号机组', 0, 'PS00', 'PS00-EM-01', 'YS_AX', 0, 'seed', NOW(), '在用末级类别'),
 (2002, '2#', '临湖一号2号机组', 0, 'PS00', 'PS00-EM-02', 'YS_LX', 0, 'seed', NOW(), '老机组挂着已停用类别YS_LX，旧挂原样留'),
 (2011, '1#', '老闸口1号机组',   1, 'PS01', 'PS01-EM-01', 'HL_HB', 0, 'seed', NOW(), '站已停用：不开新运行单，机组与老单原样留'),
 (2021, '1#', '河湾二号1号机组', 2, 'PS02', 'PS02-EM-01', 'YS_AX', 0, 'seed', NOW(), NULL)
ON DUPLICATE KEY UPDATE unit_name = VALUES(unit_name), site_id = VALUES(site_id),
 site_no = VALUES(site_no), item_code = VALUES(item_code), site_type = VALUES(site_type), del_flag = 0;

-- 片区授权底册：登录名 -> 管辖片区（文案同 t_drain_station.road_name）
INSERT INTO t_drain_area_grant (id, username, road_name, del_flag, create_by, create_time, remark)
VALUES
 (3001, 'duty_li',   '城东片区', 0, 'seed', NOW(), '只管城东：PS00'),
 (3002, 'duty_wang', '城西片区', 0, 'seed', NOW(), '只管城西：停用站 PS01（老单照见，新单开不出）'),
 (3003, 'duty_li',   '城北片区', 0, 'seed', NOW(), '兼管城北：PS02（一人可授多片）')
ON DUPLICATE KEY UPDATE road_name = VALUES(road_name), del_flag = 0;
