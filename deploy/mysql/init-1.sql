CREATE DATABASE IF NOT EXISTS tradesim_1 DEFAULT CHARSET utf8mb4;
USE tradesim_1;
CREATE TABLE IF NOT EXISTS t_order_0 (
  id            BIGINT      NOT NULL COMMENT '基因法 ID，低 6 位 = user_id 低 6 位',
  user_id       BIGINT      NOT NULL,
  contest_id    BIGINT      NOT NULL,
  client_key    VARCHAR(64) NOT NULL COMMENT '幂等键（客户端生成）',
  symbol        VARCHAR(16) NOT NULL,
  side          TINYINT     NOT NULL COMMENT '1买 2卖',
  type          TINYINT     NOT NULL COMMENT '1市价 2限价 3止损',
  price         BIGINT      NOT NULL DEFAULT 0 COMMENT '限价(分)；市价单为 0',
  stop_price    BIGINT      NOT NULL DEFAULT 0 COMMENT '止损触发价(分)',
  qty           BIGINT      NOT NULL,
  filled_qty    BIGINT      NOT NULL DEFAULT 0,
  filled_amount BIGINT      NOT NULL DEFAULT 0 COMMENT '累计成交额(分)',
  frozen        BIGINT      NOT NULL COMMENT '下单冻结：买=分，卖=股',
  used          BIGINT      NOT NULL DEFAULT 0 COMMENT '已消耗冻结：买=成交额+佣金，卖=股',
  status        TINYINT     NOT NULL COMMENT '0新建 1部成 2全成 3已撤 4拒绝',
  reject_reason VARCHAR(64) NULL,
  trading_day   INT         NOT NULL,
  node          INT         NOT NULL DEFAULT 0 COMMENT '撮合归属节点（重启恢复订单簿用）',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_client (user_id, client_key),
  KEY idx_user_contest_time (user_id, contest_id, created_at),
  KEY idx_open (node, status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_order_1 (
  id            BIGINT      NOT NULL COMMENT '基因法 ID，低 6 位 = user_id 低 6 位',
  user_id       BIGINT      NOT NULL,
  contest_id    BIGINT      NOT NULL,
  client_key    VARCHAR(64) NOT NULL COMMENT '幂等键（客户端生成）',
  symbol        VARCHAR(16) NOT NULL,
  side          TINYINT     NOT NULL COMMENT '1买 2卖',
  type          TINYINT     NOT NULL COMMENT '1市价 2限价 3止损',
  price         BIGINT      NOT NULL DEFAULT 0 COMMENT '限价(分)；市价单为 0',
  stop_price    BIGINT      NOT NULL DEFAULT 0 COMMENT '止损触发价(分)',
  qty           BIGINT      NOT NULL,
  filled_qty    BIGINT      NOT NULL DEFAULT 0,
  filled_amount BIGINT      NOT NULL DEFAULT 0 COMMENT '累计成交额(分)',
  frozen        BIGINT      NOT NULL COMMENT '下单冻结：买=分，卖=股',
  used          BIGINT      NOT NULL DEFAULT 0 COMMENT '已消耗冻结：买=成交额+佣金，卖=股',
  status        TINYINT     NOT NULL COMMENT '0新建 1部成 2全成 3已撤 4拒绝',
  reject_reason VARCHAR(64) NULL,
  trading_day   INT         NOT NULL,
  node          INT         NOT NULL DEFAULT 0 COMMENT '撮合归属节点（重启恢复订单簿用）',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_client (user_id, client_key),
  KEY idx_user_contest_time (user_id, contest_id, created_at),
  KEY idx_open (node, status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_order_2 (
  id            BIGINT      NOT NULL COMMENT '基因法 ID，低 6 位 = user_id 低 6 位',
  user_id       BIGINT      NOT NULL,
  contest_id    BIGINT      NOT NULL,
  client_key    VARCHAR(64) NOT NULL COMMENT '幂等键（客户端生成）',
  symbol        VARCHAR(16) NOT NULL,
  side          TINYINT     NOT NULL COMMENT '1买 2卖',
  type          TINYINT     NOT NULL COMMENT '1市价 2限价 3止损',
  price         BIGINT      NOT NULL DEFAULT 0 COMMENT '限价(分)；市价单为 0',
  stop_price    BIGINT      NOT NULL DEFAULT 0 COMMENT '止损触发价(分)',
  qty           BIGINT      NOT NULL,
  filled_qty    BIGINT      NOT NULL DEFAULT 0,
  filled_amount BIGINT      NOT NULL DEFAULT 0 COMMENT '累计成交额(分)',
  frozen        BIGINT      NOT NULL COMMENT '下单冻结：买=分，卖=股',
  used          BIGINT      NOT NULL DEFAULT 0 COMMENT '已消耗冻结：买=成交额+佣金，卖=股',
  status        TINYINT     NOT NULL COMMENT '0新建 1部成 2全成 3已撤 4拒绝',
  reject_reason VARCHAR(64) NULL,
  trading_day   INT         NOT NULL,
  node          INT         NOT NULL DEFAULT 0 COMMENT '撮合归属节点（重启恢复订单簿用）',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_client (user_id, client_key),
  KEY idx_user_contest_time (user_id, contest_id, created_at),
  KEY idx_open (node, status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_order_3 (
  id            BIGINT      NOT NULL COMMENT '基因法 ID，低 6 位 = user_id 低 6 位',
  user_id       BIGINT      NOT NULL,
  contest_id    BIGINT      NOT NULL,
  client_key    VARCHAR(64) NOT NULL COMMENT '幂等键（客户端生成）',
  symbol        VARCHAR(16) NOT NULL,
  side          TINYINT     NOT NULL COMMENT '1买 2卖',
  type          TINYINT     NOT NULL COMMENT '1市价 2限价 3止损',
  price         BIGINT      NOT NULL DEFAULT 0 COMMENT '限价(分)；市价单为 0',
  stop_price    BIGINT      NOT NULL DEFAULT 0 COMMENT '止损触发价(分)',
  qty           BIGINT      NOT NULL,
  filled_qty    BIGINT      NOT NULL DEFAULT 0,
  filled_amount BIGINT      NOT NULL DEFAULT 0 COMMENT '累计成交额(分)',
  frozen        BIGINT      NOT NULL COMMENT '下单冻结：买=分，卖=股',
  used          BIGINT      NOT NULL DEFAULT 0 COMMENT '已消耗冻结：买=成交额+佣金，卖=股',
  status        TINYINT     NOT NULL COMMENT '0新建 1部成 2全成 3已撤 4拒绝',
  reject_reason VARCHAR(64) NULL,
  trading_day   INT         NOT NULL,
  node          INT         NOT NULL DEFAULT 0 COMMENT '撮合归属节点（重启恢复订单簿用）',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_client (user_id, client_key),
  KEY idx_user_contest_time (user_id, contest_id, created_at),
  KEY idx_open (node, status)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_trade_0 (
  id          BIGINT      NOT NULL,
  order_id    BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  symbol      VARCHAR(16) NOT NULL,
  side        TINYINT     NOT NULL,
  price       BIGINT      NOT NULL,
  qty         BIGINT      NOT NULL,
  amount      BIGINT      NOT NULL,
  commission  BIGINT      NOT NULL,
  tax         BIGINT      NOT NULL,
  trading_day INT         NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_order (order_id),
  KEY idx_user_contest_time (user_id, contest_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_trade_1 (
  id          BIGINT      NOT NULL,
  order_id    BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  symbol      VARCHAR(16) NOT NULL,
  side        TINYINT     NOT NULL,
  price       BIGINT      NOT NULL,
  qty         BIGINT      NOT NULL,
  amount      BIGINT      NOT NULL,
  commission  BIGINT      NOT NULL,
  tax         BIGINT      NOT NULL,
  trading_day INT         NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_order (order_id),
  KEY idx_user_contest_time (user_id, contest_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_trade_2 (
  id          BIGINT      NOT NULL,
  order_id    BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  symbol      VARCHAR(16) NOT NULL,
  side        TINYINT     NOT NULL,
  price       BIGINT      NOT NULL,
  qty         BIGINT      NOT NULL,
  amount      BIGINT      NOT NULL,
  commission  BIGINT      NOT NULL,
  tax         BIGINT      NOT NULL,
  trading_day INT         NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_order (order_id),
  KEY idx_user_contest_time (user_id, contest_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_trade_3 (
  id          BIGINT      NOT NULL,
  order_id    BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  symbol      VARCHAR(16) NOT NULL,
  side        TINYINT     NOT NULL,
  price       BIGINT      NOT NULL,
  qty         BIGINT      NOT NULL,
  amount      BIGINT      NOT NULL,
  commission  BIGINT      NOT NULL,
  tax         BIGINT      NOT NULL,
  trading_day INT         NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_order (order_id),
  KEY idx_user_contest_time (user_id, contest_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_account_0 (
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  init_cash   BIGINT      NOT NULL,
  cash        BIGINT      NOT NULL COMMENT '可用(分)。增量更新，不加非负约束：消息可能乱序',
  frozen      BIGINT      NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, contest_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_account_1 (
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  init_cash   BIGINT      NOT NULL,
  cash        BIGINT      NOT NULL COMMENT '可用(分)。增量更新，不加非负约束：消息可能乱序',
  frozen      BIGINT      NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, contest_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_account_2 (
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  init_cash   BIGINT      NOT NULL,
  cash        BIGINT      NOT NULL COMMENT '可用(分)。增量更新，不加非负约束：消息可能乱序',
  frozen      BIGINT      NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, contest_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_account_3 (
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  init_cash   BIGINT      NOT NULL,
  cash        BIGINT      NOT NULL COMMENT '可用(分)。增量更新，不加非负约束：消息可能乱序',
  frozen      BIGINT      NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, contest_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_position_0 (
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  symbol      VARCHAR(16) NOT NULL,
  qty         BIGINT      NOT NULL DEFAULT 0,
  frozen      BIGINT      NOT NULL DEFAULT 0,
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, contest_id, symbol)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_position_1 (
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  symbol      VARCHAR(16) NOT NULL,
  qty         BIGINT      NOT NULL DEFAULT 0,
  frozen      BIGINT      NOT NULL DEFAULT 0,
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, contest_id, symbol)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_position_2 (
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  symbol      VARCHAR(16) NOT NULL,
  qty         BIGINT      NOT NULL DEFAULT 0,
  frozen      BIGINT      NOT NULL DEFAULT 0,
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, contest_id, symbol)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_position_3 (
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  symbol      VARCHAR(16) NOT NULL,
  qty         BIGINT      NOT NULL DEFAULT 0,
  frozen      BIGINT      NOT NULL DEFAULT 0,
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, contest_id, symbol)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_fund_flow_0 (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  biz_id      BIGINT      NOT NULL COMMENT '订单号/成交号',
  type        VARCHAR(16) NOT NULL COMMENT 'INIT/FREEZE/SETTLE/RELEASE',
  symbol      VARCHAR(16) NULL,
  cash_delta  BIGINT      NOT NULL DEFAULT 0,
  frozen_delta BIGINT     NOT NULL DEFAULT 0,
  qty_delta   BIGINT      NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz (user_id, biz_id, type) COMMENT '消息幂等',
  KEY idx_user_contest (user_id, contest_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_fund_flow_1 (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  biz_id      BIGINT      NOT NULL COMMENT '订单号/成交号',
  type        VARCHAR(16) NOT NULL COMMENT 'INIT/FREEZE/SETTLE/RELEASE',
  symbol      VARCHAR(16) NULL,
  cash_delta  BIGINT      NOT NULL DEFAULT 0,
  frozen_delta BIGINT     NOT NULL DEFAULT 0,
  qty_delta   BIGINT      NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz (user_id, biz_id, type) COMMENT '消息幂等',
  KEY idx_user_contest (user_id, contest_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_fund_flow_2 (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  biz_id      BIGINT      NOT NULL COMMENT '订单号/成交号',
  type        VARCHAR(16) NOT NULL COMMENT 'INIT/FREEZE/SETTLE/RELEASE',
  symbol      VARCHAR(16) NULL,
  cash_delta  BIGINT      NOT NULL DEFAULT 0,
  frozen_delta BIGINT     NOT NULL DEFAULT 0,
  qty_delta   BIGINT      NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz (user_id, biz_id, type) COMMENT '消息幂等',
  KEY idx_user_contest (user_id, contest_id, created_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS t_fund_flow_3 (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL,
  contest_id  BIGINT      NOT NULL,
  biz_id      BIGINT      NOT NULL COMMENT '订单号/成交号',
  type        VARCHAR(16) NOT NULL COMMENT 'INIT/FREEZE/SETTLE/RELEASE',
  symbol      VARCHAR(16) NULL,
  cash_delta  BIGINT      NOT NULL DEFAULT 0,
  frozen_delta BIGINT     NOT NULL DEFAULT 0,
  qty_delta   BIGINT      NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz (user_id, biz_id, type) COMMENT '消息幂等',
  KEY idx_user_contest (user_id, contest_id, created_at)
) ENGINE=InnoDB;
