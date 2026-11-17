CREATE DATABASE IF NOT EXISTS tradesim_0 DEFAULT CHARSET utf8mb4;
-- 非分片的全局库（用户、比赛）只放在 ds0
CREATE DATABASE IF NOT EXISTS tradesim_global DEFAULT CHARSET utf8mb4;
USE tradesim_global;

CREATE TABLE IF NOT EXISTS t_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  username      VARCHAR(32)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  nickname      VARCHAR(32)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_username (username)
) AUTO_INCREMENT = 10001;

CREATE TABLE IF NOT EXISTS t_contest (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(64) NOT NULL,
  init_cash   BIGINT      NOT NULL COMMENT '初始资金（分）',
  start_at    DATETIME(3) NOT NULL,
  end_at      DATETIME(3) NOT NULL,
  status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0报名中 1进行中 2已结束',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE IF NOT EXISTS t_contest_member (
  contest_id BIGINT NOT NULL,
  user_id    BIGINT NOT NULL,
  joined_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (contest_id, user_id),
  KEY idx_user (user_id)
);
