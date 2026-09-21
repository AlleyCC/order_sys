INSERT INTO `roles` (`name`, `description`) VALUES
  ('ADMIN_STAFF', '行政(維護商品主檔:分類,未來含店家與菜單)'),
  ('ACCOUNTANT',  '會計(唯讀對帳:查任一使用者交易紀錄;儲值端點完成後再追加授權)');

INSERT INTO `resources` (`url_pattern`, `http_method`, `name`, `category`) VALUES
  ('/admin/orders/**',       'GET', '後台訂單查詢(唯讀)',     'admin'),
  ('/admin/transactions/**', 'GET', '後台交易紀錄查詢(唯讀)', 'admin');

INSERT INTO `role_resources` (`role_id`, `resource_id`)
SELECT r.`role_id`, res.`resource_id`
FROM `roles` r
JOIN `resources` res ON res.`url_pattern` IN (
       '/category/create_category',
       '/category/update_category',
       '/category/delete_category')
WHERE r.`name` = 'ADMIN_STAFF';

INSERT INTO `role_resources` (`role_id`, `resource_id`)
SELECT r.`role_id`, res.`resource_id`
FROM `roles` r
JOIN `resources` res ON res.`url_pattern` = '/admin/orders/**'
WHERE r.`name` = 'CUSTOMER_SERVICE';

INSERT INTO `role_resources` (`role_id`, `resource_id`)
SELECT r.`role_id`, res.`resource_id`
FROM `roles` r
JOIN `resources` res ON res.`url_pattern` = '/admin/transactions/**'
WHERE r.`name` = 'ACCOUNTANT';

DELETE FROM `roles` WHERE `name` IN ('LEADER', 'MEMBER');

ALTER TABLE `roles`
  MODIFY COLUMN `name` VARCHAR(30) NOT NULL
  COMMENT '角色代碼:SUPER_ADMIN/ADMIN_STAFF/CUSTOMER_SERVICE/ACCOUNTANT';

ALTER TABLE `users` DROP COLUMN `role`;
