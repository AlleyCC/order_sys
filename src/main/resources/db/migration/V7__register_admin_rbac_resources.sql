-- PayPool Schema V7: 登記 RBAC 管理端 API 為受管制資源
-- 不掛任何 role_resources → 僅超級管理員(授權層程式碼不變量)可呼叫。
-- 管理端保護機制因此完全走動態授權,不需要另寫 @PreAuthorize。

INSERT INTO `resources` (`url_pattern`, `http_method`, `name`, `category`) VALUES
  ('/admin/rbac/**', 'ALL', 'RBAC 權限管理', 'admin');
