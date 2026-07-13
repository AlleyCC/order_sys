-- PayPool Schema V5: categories 樂觀鎖版本欄
-- 修改分類(名稱/排序)時,用 version 偵測「兩個管理員同時改同一筆」的更新遺失(lost update)。
-- 每次成功更新 version +1;帶著過期 version 的請求會打中 0 列 → 應用層轉 409。

ALTER TABLE `categories`
  ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '樂觀鎖版本號,每次更新 +1' AFTER `sort_order`;
