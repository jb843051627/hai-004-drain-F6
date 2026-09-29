-- drain 城市排水防涝泵站运行与汛期调度管理 -- schema (hai-004)
-- 列名与基线实体契约（@TableName/@TableField）逐列对齐，改列必须同步实体。
-- 库：hai_004

CREATE TABLE IF NOT EXISTS t_drain_dispatch_bill (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '调度申请号',
  round_no int DEFAULT '1' COMMENT '申报轮次 首轮1 被驳回重报进下一轮',
  node_no int DEFAULT NULL COMMENT '当前签核节点 0值班长 1排水科 2分管领导',
  sign_mode int DEFAULT NULL COMMENT '签核模式 0或签 1会签',
  need_count int DEFAULT NULL COMMENT '本节点应签人数',
  sign_count int DEFAULT NULL COMMENT '本节点已签人数(与流水表实时重算一致,差一笔都不算过)',
  pump_scale decimal(12,2) DEFAULT NULL COMMENT '申请强排规模(m3/h) 批准后冻结',
  start_time datetime DEFAULT NULL COMMENT '申请开工时刻 批准后冻结',
  status int DEFAULT NULL COMMENT '签批单状态 0审批中 1批准强排 2已驳回(本轮终止)',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_bill_round (bill_no, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='泵站强排调度签批单';

-- 签批流水：哪个节点谁签的、什么时刻、批了哪一句，逐笔留痕，倒着能查。
-- 唯一索引 uk_bill_round_node_user：同一轮同一节点同一人只许落一笔，防重复签/替人凑数。
CREATE TABLE IF NOT EXISTS t_drain_dispatch_sign (
  id bigint NOT NULL COMMENT '主键',
  bill_id bigint NOT NULL COMMENT '签批单主键 t_drain_dispatch_bill.id',
  bill_no varchar(64) DEFAULT NULL COMMENT '调度申请号(冗余便于台账倒查)',
  round_no int NOT NULL COMMENT '签批轮次',
  node_no int NOT NULL COMMENT '签核节点 0值班长 1排水科 2分管领导',
  sign_mode int DEFAULT NULL COMMENT '签核模式快照 0或签 1会签',
  approver varchar(64) NOT NULL COMMENT '签批人登录名',
  action int NOT NULL COMMENT '动作 1批准 2驳回',
  comment varchar(500) DEFAULT NULL COMMENT '批了哪一句(签批意见)',
  sign_time datetime NOT NULL COMMENT '签批时刻',
  seq_no int NOT NULL COMMENT '单据全局时序(倒查排序用)',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bill_round_node_user (bill_id, round_no, node_no, approver),
  KEY idx_bill_round (bill_id, round_no, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='泵站强排调度签批流水';

CREATE TABLE IF NOT EXISTS t_drain_level_std (
  id bigint NOT NULL COMMENT '主键',
  rule_code varchar(64) DEFAULT NULL COMMENT '判线代号',
  rule_name varchar(128) DEFAULT NULL COMMENT '判线名称',
  th1_max decimal(12,2) DEFAULT NULL COMMENT '一档起排水位上限',
  th2_max decimal(12,2) DEFAULT NULL COMMENT '二档起排水位上限',
  th3_max decimal(12,2) DEFAULT NULL COMMENT '三档起排水位上限',
  eff_start datetime DEFAULT NULL COMMENT '生效起始时刻',
  eff_end datetime DEFAULT NULL COMMENT '失效截止时刻(不含)',
  priority int DEFAULT NULL COMMENT '取优序号(数值越大越优先)',
  status int DEFAULT NULL COMMENT '判线状态 0现行 1已作废',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='泵站起排判线标准';

CREATE TABLE IF NOT EXISTS t_drain_meter_batch (
  batch_no varchar(64) NOT NULL COMMENT '汇交批次号(幂等凭据)',
  total_count int DEFAULT NULL COMMENT '报文原行数',
  ok_count int DEFAULT NULL COMMENT '已对平行数(按头一笔回话)',
  fail_count int DEFAULT NULL COMMENT '已剔除行数(按头一笔回话)',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间(头一笔落库时刻)',
  PRIMARY KEY (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='远传计量成批汇交批次头(同批重交幂等闸门)';

CREATE TABLE IF NOT EXISTS t_drain_meter_row (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) DEFAULT NULL COMMENT '汇交批次号(断传人工补录行此列为空)',
  row_no int DEFAULT NULL COMMENT '远传报文行次',
  item_code varchar(64) DEFAULT NULL COMMENT '计量点编码',
  qty decimal(12,2) DEFAULT NULL COMMENT '本期远传读数',
  status int DEFAULT NULL COMMENT '行状态 0待对账 1已对平 2已剔除',
  source int DEFAULT '0' COMMENT '读数来源 0远传 1断传人工补录',
  read_time datetime DEFAULT NULL COMMENT '抄表时刻 仅断传人工补录照值守员实抄时间填(远传行留空,按落库时间归天,不许拿补录时刻顶替)',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_batch_row (batch_no, row_no, id),
  KEY idx_item_day (item_code, source, read_time),
  -- 同一表计同一抄表时刻、同一来源只许一笔：断传补录防重手，远传防同刻重行（NULL 不冲突）
  UNIQUE KEY uk_item_source_read (item_code, source, read_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='泵站远传计量明细行';

CREATE TABLE IF NOT EXISTS t_drain_patrol_task (
  id bigint NOT NULL COMMENT '主键',
  item_no varchar(64) DEFAULT NULL COMMENT '巡检任务编号',
  site_no varchar(64) DEFAULT NULL COMMENT '到点该巡泵站编号(空=坏行,跳过交人工核)',
  due_at datetime DEFAULT NULL COMMENT '到期时刻',
  amount decimal(12,2) DEFAULT NULL COMMENT '应巡点位数',
  status int DEFAULT NULL COMMENT '状态 0待派 1已派 2无法派单',
  assignee varchar(64) DEFAULT NULL COMMENT '受派当班值守员(派定写死,不两人轮替)',
  dispatched_at datetime DEFAULT NULL COMMENT '派定时刻(落记号)',
  shift_id bigint DEFAULT NULL COMMENT '派定时所属值守排班(t_drain_duty_shift.id,一人一时段一站的判重锚点)',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_site_status (site_no, status, due_at),
  -- 并发两道闸：待派行 shift_id/assignee 为 NULL（MySQL 唯一键里 NULL 不冲突），
  -- 一旦派定，同一值守时段(shift_id)同一人只许落一条已派、同一座站只许落一条已派——
  -- 两个轮次并发抢派时，库唯一键兜底，绝不出一人两站或一站两人。
  UNIQUE KEY uk_shift_assignee (shift_id, assignee),
  UNIQUE KEY uk_shift_site (shift_id, site_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='汛期值守巡检任务条目';

-- 汛期值守排班：系统照这张表自己派。时段半开区间[start_at,end_at)，跨天夜班照实排；
-- 某时刻没有任何一条覆盖它，就是排班空档——空档不派不吭声，条目原样留着。
CREATE TABLE IF NOT EXISTS t_drain_duty_shift (
  id bigint NOT NULL COMMENT '主键',
  shift_name varchar(64) DEFAULT NULL COMMENT '班次名(白班/夜班)',
  start_at datetime NOT NULL COMMENT '值守时段起(含)',
  end_at datetime NOT NULL COMMENT '值守时段止(不含,跨天照排)',
  duty_user varchar(64) NOT NULL COMMENT '当班值守员登录名',
  status int DEFAULT '0' COMMENT '排班状态 0有效 1停用',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_span (start_at, end_at, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='汛期泵站值守排班表';

-- 泵站类别是层级标签树：parent_id=0 为根层，leaf_flag=1 才是末级。
-- 站档案与机组挂类别只许挂末级代号(type_code)，挂到上层当场报错；
-- status=1 的老类别停用后不再可选，先前挂出去的标签原样留着，不回改不摘除。
CREATE TABLE IF NOT EXISTS t_drain_site_type (
  id bigint NOT NULL COMMENT '主键',
  type_code varchar(64) DEFAULT NULL COMMENT '类别代号(站档案/机组挂这个,未删范围内唯一)',
  type_name varchar(128) DEFAULT NULL COMMENT '类别名称',
  parent_id bigint DEFAULT '0' COMMENT '父类别主键 0=根层',
  leaf_flag int DEFAULT '0' COMMENT '末级标记 0上层 1末级(只许挂末级)',
  status int DEFAULT '0' COMMENT '状态 0在用 1已停用(停用不可新挂,旧挂原样留)',
  order_num int DEFAULT '0' COMMENT '同层排序号',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_parent (parent_id, order_num, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='泵站类别标签树(只许挂末级)';

-- 机组挂在泵站名下：一条机组一个计量点编码，远传计量(t_drain_meter_row.item_code)、
-- 台账检索、运行录单都拿它对锚。计量点编码全局唯一，逻辑删也不复用——
-- 编码是对着远传报文的外部锚点，复用会把新机组的账算到旧读数上。
CREATE TABLE IF NOT EXISTS t_drain_pump_unit (
  id bigint NOT NULL COMMENT '主键',
  unit_no varchar(64) DEFAULT NULL COMMENT '机组编号(站内未删范围内唯一)',
  unit_name varchar(128) DEFAULT NULL COMMENT '机组名称',
  site_id bigint NOT NULL COMMENT '所属泵站 t_drain_station.id',
  site_no varchar(64) DEFAULT NULL COMMENT '泵站编号(照档案回填,不信表单)',
  item_code varchar(64) DEFAULT NULL COMMENT '计量点编码(全局唯一,台账检索/远传对锚)',
  site_type varchar(32) DEFAULT NULL COMMENT '机组类别标签(末级类别代号,只许挂末级在用类别)',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_item_code (item_code),
  KEY idx_site (site_id, del_flag, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='泵站机组档案(挂类别末级标签)';

-- 片区授权：登录名 -> 管辖片区(与 t_drain_station.road_name 同文案)。
-- 各片区的人只看得见授权片区名下的泵站/机组/运行单——查询 where 层就裁掉，不是界面藏。
-- 管理员角色与 admin 不走这张表（全片可见）；表里一行没有的普通登录人一片都看不到。
CREATE TABLE IF NOT EXISTS t_drain_area_grant (
  id bigint NOT NULL COMMENT '主键',
  username varchar(64) NOT NULL COMMENT '被授权登录名',
  road_name varchar(128) NOT NULL COMMENT '管辖片区(同 t_drain_station.road_name)',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_road (username, road_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录人片区授权(数据权限底册)';

CREATE TABLE IF NOT EXISTS t_drain_run_record (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '运行单号(人手填,未删范围内唯一)',
  unit_id bigint DEFAULT NULL COMMENT '受录机组 t_drain_pump_unit.id(一机组一年一单锚点)',
  site_id bigint DEFAULT NULL COMMENT '受录泵站(照机组所属站回填)',
  site_no varchar(64) DEFAULT NULL COMMENT '泵站编号(照档案回填)',
  run_year int DEFAULT NULL COMMENT '运行年度(系统登记年,不由表单给)',
  qty decimal(12,2) DEFAULT NULL COMMENT '机组实际运行时长(小时)',
  fine_amt decimal(12,2) DEFAULT NULL COMMENT '电耗核算费(元,系统照时长×单价核算,人不动手)',
  grade_level int DEFAULT NULL COMMENT '运行评定档(系统照档案三档上限折,人不动手)',
  status int DEFAULT NULL COMMENT '状态 0待复核 1已复核 2已封存',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  -- 单号手填也不许重：库唯一键兜底并发两笔同号
  UNIQUE KEY uk_bill_no (bill_no),
  -- 一机组一年只许一单：库唯一键钉死，同年第二张当场撞键退回
  UNIQUE KEY uk_unit_year (unit_id, run_year),
  KEY idx_site_year (site_no, run_year, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机组运行记录单(一机组一年一单)';

CREATE TABLE IF NOT EXISTS t_drain_station (
  id bigint NOT NULL COMMENT '主键',
  site_no varchar(64) DEFAULT NULL COMMENT '泵站编号',
  site_name varchar(128) DEFAULT NULL COMMENT '泵站名称',
  site_type varchar(32) DEFAULT NULL COMMENT '泵站类别',
  road_name varchar(128) DEFAULT NULL COMMENT '管辖片区',
  th1_max decimal(12,2) DEFAULT NULL COMMENT '本泵站考核一档上限',
  th2_max decimal(12,2) DEFAULT NULL COMMENT '本泵站考核二档上限',
  th3_max decimal(12,2) DEFAULT NULL COMMENT '本泵站考核三档上限',
  status int DEFAULT NULL COMMENT '档案状态 0在用 1已停用',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='在册泵站档案';

CREATE TABLE IF NOT EXISTS t_drain_work_order (
  id bigint NOT NULL COMMENT '主键',
  biz_no varchar(64) DEFAULT NULL COMMENT '作业单号',
  site_no varchar(64) DEFAULT NULL COMMENT '修单所压泵站编号(在办/退回单挂着的站,巡检自动派单本轮往后推)',
  stage int DEFAULT NULL COMMENT '当前环节 0..4',
  status int DEFAULT NULL COMMENT '作业单状态 0在办 1办结确认 2退回重办',
  content varchar(255) DEFAULT NULL COMMENT '推进记录',
  last_action varchar(64) DEFAULT NULL COMMENT '最近一次推进动作',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_site_open (site_no, status, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='排涝抢险作业单';

-- 抢险作业单：接单(0)->到场布防(1)->处置(2)->撤场(3)->办结销号(4) 五档一道一道走。
-- 主表只记"现在停在哪一档、什么状态"；每档谁办的、什么时刻、办了什么，全在流水表
-- t_drain_rescue_step 里逐笔落账，倒着一级级盘得出来。
-- 销号(1)即终态：主表内容不许再改、整单不许删（本表干脆不提供删除入口），
-- 确有错漏另开补单（supplement_of 挂回母单），母单一笔不动。
CREATE TABLE IF NOT EXISTS t_drain_rescue_order (
  id bigint NOT NULL COMMENT '主键',
  biz_no varchar(64) DEFAULT NULL COMMENT '抢险作业单号',
  site_no varchar(64) DEFAULT NULL COMMENT '抢险所压泵站编号',
  stage int DEFAULT NULL COMMENT '当前档 0接单 1到场布防 2处置 3撤场 4办结销号',
  status int DEFAULT NULL COMMENT '作业单状态 0在办 1已销号(终态) 2退回重办(停在退回档,重办后才许再推进)',
  supplement_of bigint DEFAULT NULL COMMENT '补单所补母单主键(仅补单有值;母单本身此列为空)',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_site_open (site_no, status, del_flag),
  KEY idx_supplement_of (supplement_of)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='抢险作业单(五档流转)';

-- 抢险档级流水：每挪一档落一笔——哪一档、第几回办、谁办的、什么时刻、办了什么。
-- 口径：
-- 1. 同一档同一办次(attempt_no)只许一笔（uk_order_stage_attempt 兜底），
--    不重办就挪不出第二回——"同一档不许挪第二回"由库唯一键钉死；
-- 2. 撤回重办：退回目标档的现行办理笔置 void_flag=1 作废（谁撤的、什么时刻、
--    为什么撤一并记下），作废笔不进单面——不许跟重办后的新记录并排挂在同一
--    张单上；但行留在库里，倒查/审计翻得到；
-- 3. 流水只增不改：没有更新办理内容的入口，写错只能撤回作废后重办落新笔；
-- 4. seq_no 单据全局递增，倒序一拉就是一级级倒着盘。
CREATE TABLE IF NOT EXISTS t_drain_rescue_step (
  id bigint NOT NULL COMMENT '主键',
  order_id bigint NOT NULL COMMENT '抢险作业单主键 t_drain_rescue_order.id',
  biz_no varchar(64) DEFAULT NULL COMMENT '抢险作业单号(冗余便于台账倒查)',
  stage int NOT NULL COMMENT '档位 0接单 1到场布防 2处置 3撤场 4办结销号',
  attempt_no int NOT NULL COMMENT '本档第几回办 首办1 撤回重办一次+1',
  operator varchar(64) NOT NULL COMMENT '经办人登录名',
  action int NOT NULL COMMENT '动作 1办理(撤回作废不另起行,看 void_flag/void_by/void_time/void_reason)',
  detail varchar(500) DEFAULT NULL COMMENT '本档办了什么(办理记录)',
  op_time datetime NOT NULL COMMENT '经办时刻',
  seq_no int NOT NULL COMMENT '单据全局时序(倒查排序用)',
  void_flag int DEFAULT '0' COMMENT '作废标记 0现行 1已作废(撤回重办后不许再挂上单面)',
  void_by varchar(64) DEFAULT NULL COMMENT '作废经办人(谁撤的)',
  void_time datetime DEFAULT NULL COMMENT '作废时刻',
  void_reason varchar(500) DEFAULT NULL COMMENT '作废缘由(撤回批语)',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_stage_attempt (order_id, stage, attempt_no),
  KEY idx_order_seq (order_id, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='抢险作业单档级流水';
