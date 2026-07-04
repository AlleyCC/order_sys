-- PayPool Schema V4: categories (分類)
-- 商品分類管理,僅管理員可新增。名稱在「有效」資料中須唯一(軟刪除後可重用同名)。

CREATE TABLE `categories` (
  `category_id` INT         NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(50) NOT NULL                COMMENT '分類名稱',
  `sort_order`  INT         NOT NULL DEFAULT 0      COMMENT '排序值,越小越前',
  `is_deleted`  TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '軟刪除:0=有效,1=已刪',
  `active_flag` TINYINT     GENERATED ALWAYS AS (IF(`is_deleted` = 0, 1, NULL)) VIRTUAL
                                                    COMMENT '唯一約束輔助欄(衍生,勿手寫):有效=1,已刪=NULL',
  `created_by`  VARCHAR(64) NOT NULL,
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_by`  VARCHAR(64) NULL,
  `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`category_id`),
  -- 利用 MySQL「NULL 互不相同」特性:有效列 active_flag=1 → 同名相撞;已刪列 active_flag=NULL → 可重複
  UNIQUE KEY `uk_name_active` (`name`, `active_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
