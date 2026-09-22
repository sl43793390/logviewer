-- ----------------------------
-- Table structure for users（系统用户，登录认证与权限判断都取自这张表）
--   id_user      登录名，主键，创建后不允许修改
--   name_user    姓名，仅用于展示
--   password     SM3 摘要，大写十六进制，明文不落库
--   create_time  创建时间，文本列，格式 yyyy-MM-dd HH:mm:ss
--   email        邮箱
--   organization 所属组织
--   cd_phone     手机号
--   expire_time  有效期至，文本列；为空表示长期有效，当天 23:59:59 之前仍可登录。
--                纯日期（date('now')，如 2026-12-20）与完整时间戳
--                （yyyy-MM-dd HH:mm:ss）两种写法都能读：读取走 TextDateTypeHandler
--                的宽容解析，写入一律用 yyyy-MM-dd HH:mm:ss
--   user_flag    '1' 或空 = 启用，'0' = 禁用（禁用后无法登录）
--   permission   逗号分隔的权限串：ADD 新增 / DELETE 删除 / UPDATE 修改 /
--                QUERY 查询 / UPLOAD 上传，ALL 表示全部权限
--
-- 这一段是可重复执行的：建表用 IF NOT EXISTS，插入用 INSERT OR IGNORE，
-- 因此不会把已有账号冲掉。内置管理员 admin 另有兜底——DbInitializer 在每次
-- 启动时都会检查它是否存在且可用（见 src/main/java/com/so/util/DbInitializer.java），
-- 即便下面这行 INSERT 被 IGNORE 跳过，也不会出现"谁都登不进去"的情况。
-- 表结构必须与 DbInitializer.COLUMNS 保持一致，改一处要改两处。
-- ----------------------------
CREATE TABLE IF NOT EXISTS "users" (
                         "id_user" TEXT(50) NOT NULL,
                         "name_user" TEXT(50),
                         "password" TEXT(64),
                         "create_time" text(32),
                         "email" TEXT(64),
                         "organization" TEXT(64),
                         "cd_phone" TEXT(32),
                         "expire_time" TEXT(32),
                         "user_flag" TEXT(1),
                         "permission" TEXT,
                         PRIMARY KEY ("id_user")
);

INSERT OR IGNORE INTO "users" VALUES ('admin', '系统管理员', 'DC1FD00E3EEEB940FF46F457BF97D66BA7FCC36E0B20802383DE142860E76AE6', datetime('now','localtime'), 'admin@example.com', '运维部', '13800000000', NULL, '1', 'ALL');
INSERT OR IGNORE INTO "users" VALUES ('test', '测试账号', '55E12E91650D2FEC56EC74E1D3E4DDBFCE2EF3A65890C2A19ECF88A307E76A23', datetime('now','localtime'), 'test@example.com', '测试组', '13900000001', date('now','+90 day'), '1', 'ADD,');
INSERT OR IGNORE INTO "users" VALUES ('developer', '开发账号', '82BE8D25346156C92E408CF511675D9075EEB955D9C5FD9A57505D9CDFBBAAC9', datetime('now','localtime'), 'dev@example.com', '研发部', '13900000002', date('now','+30 day'), '1', 'ADD,');
-- 下面两条是演示数据，用来看"已禁用""已过期""7 天内到期"这几种状态在页面上的样子，不需要可以直接在用户管理页删掉
INSERT OR IGNORE INTO "users" VALUES ('guest', '访客演示', 'DC1FD00E3EEEB940FF46F457BF97D66BA7FCC36E0B20802383DE142860E76AE6', datetime('now','localtime'), 'guest@example.com', '外部合作方', '13900000003', NULL, '0', 'QUERY,');
INSERT OR IGNORE INTO "users" VALUES ('temp', '临时账号', '55E12E91650D2FEC56EC74E1D3E4DDBFCE2EF3A65890C2A19ECF88A307E76A23', datetime('now','-60 day'), 'temp@example.com', '外包团队', '13900000004', date('now','-5 day'), '1', 'ADD,QUERY,');


DROP TABLE IF EXISTS "common_project_mgmt";
CREATE TABLE "common_project_mgmt" (
                                       "id_host" TEXT NOT NULL,
                                       "id_project" TEXT NOT NULL,
                                       "name_project" TEXT NOT NULL,
                                       "cd_path" TEXT NOT NULL,
                                       "cmd_start" TEXT,
                                       "cmd_stop" TEXT,
                                       "cmd_restart" TEXT,
                                       "cmd_refresh" TEXT,
                                       "cmd_status" TEXT,
                                       "cmd_status_success_key" TEXT,
                                       "cd_description" TEXT,
                                       "cd_tag" TEXT,
                                       PRIMARY KEY ("id_host", "id_project")
);
INSERT INTO "common_project_mgmt" ("id_host", "id_project", "name_project", "cd_path", "cmd_start", "cmd_stop", "cmd_restart", "cmd_refresh", "cmd_status", "cmd_status_success_key", "cd_description", "cd_tag") VALUES ('192.168.190.160', 'nginx', 'nginx', '/usr/local/nginx/sbin', './nginx ', './nginx -s stop', '', './nginx -s reload', 'ps -ef | grep nginx', 'nginx: master', 'nginx配置示例', '');

-- ----------------------------
-- Table structure for connection_info
-- ----------------------------
DROP TABLE IF EXISTS "connection_info";
CREATE TABLE "connection_info" (
                                   "id_host" TEXT NOT NULL,
                                   "cd_port" TEXT NOT NULL,
                                   "id_user" TEXT NOT NULL,
                                   "cd_password" TEXT,
                                   "cd_key_path" TEXT,
                                   "cd_logpath" TEXT,
                                   "desc" TEXT,
                                   PRIMARY KEY ("id_host", "cd_port", "id_user")
);

-- ----------------------------
-- Records of connection_info
-- ----------------------------
INSERT INTO "connection_info" VALUES ('192.168.190.100', '22', 'root', 'test', NULL, NULL,'测试');

-- ----------------------------
-- Table structure for log_path
-- ----------------------------
DROP TABLE IF EXISTS "log_path";
CREATE TABLE "log_path" (
                            "id_loghost" TEXT(32) NOT NULL,
                            "id_log_path" TEXT(64) NOT NULL,
                            "name_log" TEXT(32),
                            PRIMARY KEY ("id_loghost", "id_log_path")
);

DROP TABLE IF EXISTS "projects";
CREATE TABLE "projects" (
                            "id_host" TEXT(32) NOT NULL,
                            "id_project" TEXT(64) NOT NULL,
                            "name_project" TEXT(32),
                            "cd_parent_path" TEXT(128) NOT NULL,
                            "cd_tag" TEXT(32),
                            "cd_command" TEXT(128),
                            "jvm_param" TEXT(128),
                            "jar_param" TEXT(64),
                            "jar_name" TEXT(128),
                            "cd_description" TEXT(128),
                            PRIMARY KEY ("id_host", "id_project")
);


-- ----------------------------
-- Table structure for tomcat_info
-- ----------------------------
DROP TABLE IF EXISTS "tomcat_info";
CREATE TABLE "tomcat_info" (
                               "id_host" TEXT NOT NULL,
                               "tomcat_id" text(50) NOT NULL,
                               "name_tomcat" TEXT(64),
                               "tomcat_path" text(100),
                               "webapp_path" text(100),
                               "tag" TEXT(32),
                               "cd_description" TEXT(150),
                               PRIMARY KEY ("id_host", "tomcat_id")
);
