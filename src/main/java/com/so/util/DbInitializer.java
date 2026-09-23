package com.so.util;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.so.entity.User;
import com.so.mapper.UserDao;

import cn.hutool.core.util.StrUtil;

/**
 * 数据库初始化：保证 {@code users} 表存在、字段齐全，并且内置管理员 {@code admin}
 * 一定在库里、一定能登录。
 * <p>
 * 为什么需要它：登录认证读的是数据库，历史实现却把"默认用户"放在运行目录的
 * {@code users.properties} 里。那个文件丢了、没拷、编码错了，就会出现"库是空的、
 * 谁都登不进去、也没人能进页面把账号建出来"的死局——引导入口不能依赖外部文件。
 * 现在改成由代码在启动时幂等写入，删掉 {@code users.properties} 也不会锁死系统。
 * <p>
 * 三个保证，都是幂等的：
 * <ol>
 * <li>表不存在则建表，缺字段则补字段（从旧版本升级上来的库可以直接用，不用手工改表）；</li>
 * <li>{@code admin} 不存在则按默认密码写入；</li>
 * <li>{@code admin} 存在但已经登不进去（密码为空 / 被禁用 / 已过期 / 权限被摘），
 * 自动修回可用状态。</li>
 * </ol>
 */
public class DbInitializer {

	private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

	private static final String TABLE = "users";

	/**
	 * 表结构与 {@code User} 实体的 {@code @TableId} / {@code @TableField} 一一对应，
	 * 增删字段时两边必须同时改，否则 MyBatis-Plus 拼出来的 SQL 会找不到列。
	 */
	private static final String[][] COLUMNS = {
			{ "id_user", "TEXT(50)" },
			{ "name_user", "TEXT(50)" },
			{ "password", "TEXT(64)" },
			{ "create_time", "TEXT(32)" },
			{ "email", "TEXT(64)" },
			{ "organization", "TEXT(64)" },
			{ "cd_phone", "TEXT(32)" },
			{ "expire_time", "TEXT(32)" },
			{ "user_flag", "TEXT(1)" },
			{ "permission", "TEXT" } };

	/**
	 * 确保内置管理员可用。登录页每次进入都会调用，正常路径下只有一次主键查询的开销。
	 *
	 * @param dataSource 数据源，用于建表 / 补字段
	 * @param userDao    用户 DAO，用于读写 admin 这条记录
	 */
	public static void ensureAdminUser(DataSource dataSource, UserDao userDao) {
		if (null == dataSource || null == userDao) {
			log.warn("数据源或 UserDao 未就绪，跳过管理员初始化");
			return;
		}
		// 多个会话可能同时打开登录页，建表 / 插入加锁串行执行，避免撞 SQLite 写锁
		synchronized (DbInitializer.class) {
			try {
				ensureUserTable(dataSource);
			} catch (Exception e) {
				log.error("初始化 {} 表失败，管理员账号可能不可用", TABLE, e);
				return;
			}
			try {
				ensureAdminRow(userDao);
			} catch (Exception e) {
				log.error("检查内置管理员失败", e);
			}
		}
	}

	/**
	 * 表不存在就建，缺字段就补。
	 * <p>
	 * 用 {@code SELECT * ... WHERE 1 = 0} 读列名而不是 {@code PRAGMA table_info}：
	 * 前者是标准 SQL，换数据库也不用改，且不产生任何数据副作用。
	 */
	private static void ensureUserTable(DataSource dataSource) throws SQLException {
		try (Connection conn = dataSource.getConnection()) {
			List<String> existing = null;
			try {
				existing = readColumns(conn);
			} catch (SQLException e) {
				// 查询失败基本只有一个原因：表还不存在
				log.info("{} 表不存在，准备创建", TABLE);
			}
			if (null == existing) {
				try (Statement st = conn.createStatement()) {
					st.executeUpdate(buildCreateSql());
				}
				log.info("已创建 {} 表", TABLE);
				return;
			}
			List<String> added = new ArrayList<String>();
			for (String[] column : COLUMNS) {
				if (existing.contains(column[0])) {
					continue;
				}
				// SQLite 的 ADD COLUMN 不接受 NOT NULL（除非给默认值），
				// 补列只补类型，主键约束由原表保留
				try (Statement st = conn.createStatement()) {
					st.executeUpdate("ALTER TABLE \"" + TABLE + "\" ADD COLUMN \"" + column[0] + "\" " + column[1]);
				}
				added.add(column[0]);
			}
			if (!added.isEmpty()) {
				log.warn("{} 表缺少字段 {}，已自动补齐", TABLE, added);
			}
		}
	}

	private static List<String> readColumns(Connection conn) throws SQLException {
		List<String> names = new ArrayList<String>();
		try (Statement st = conn.createStatement();
				ResultSet rs = st.executeQuery("SELECT * FROM \"" + TABLE + "\" WHERE 1 = 0")) {
			ResultSetMetaData meta = rs.getMetaData();
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				names.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
			}
		}
		return names;
	}

	private static String buildCreateSql() {
		StringBuilder sql = new StringBuilder();
		sql.append("CREATE TABLE IF NOT EXISTS \"").append(TABLE).append("\" (");
		for (String[] column : COLUMNS) {
			sql.append("\"").append(column[0]).append("\" ").append(column[1]);
			if ("id_user".equals(column[0])) {
				sql.append(" NOT NULL");
			}
			sql.append(", ");
		}
		sql.append("PRIMARY KEY (\"id_user\"))");
		return sql.toString();
	}

	/**
	 * admin 不存在则写入；存在但状态已经登不进去则修回来。
	 * <p>
	 * 只修复"会导致无法登录"的项，不碰姓名、邮箱这些展示信息。
	 */
	private static void ensureAdminRow(UserDao userDao) {
		User admin = userDao.selectById(User.ADMIN_USER_ID);
		if (null == admin) {
			userDao.insert(buildDefaultAdmin());
			log.warn("数据库中不存在内置管理员 {}，已自动创建，初始密码 {}，请登录后立即修改",
					User.ADMIN_USER_ID, User.ADMIN_DEFAULT_PASSWORD);
			return;
		}
		List<String> repairs = new ArrayList<String>();
		if (StrUtil.isBlank(admin.getPassword())) {
			admin.setPassword(Util.getSm3DigestStr(User.ADMIN_DEFAULT_PASSWORD));
			repairs.add("密码为空，已重置为默认密码 " + User.ADMIN_DEFAULT_PASSWORD);
		}
		if (!admin.isEnabled()) {
			admin.setUserFlag(User.FLAG_ENABLED);
			repairs.add("处于禁用状态，已恢复启用");
		}
		if (admin.isExpired()) {
			// 置 null 后能真正写进库：User.expireTime 的 updateStrategy 是 IGNORED
			admin.setExpireTime(null);
			repairs.add("已过期，已改为长期有效");
		}
		if (!admin.isAllPermission()) {
			admin.setPermission(Constants.ALL);
			repairs.add("权限不含 " + Constants.ALL + "，已恢复为全部权限");
		}
		if (!repairs.isEmpty()) {
			userDao.updateById(admin);
			log.warn("内置管理员 {} 状态异常，已自动修复：{}", User.ADMIN_USER_ID, StrUtil.join("；", repairs));
		}
	}

	private static User buildDefaultAdmin() {
		User admin = new User();
		admin.setUserId(User.ADMIN_USER_ID);
		admin.setUserName("系统管理员");
		admin.setPassword(Util.getSm3DigestStr(User.ADMIN_DEFAULT_PASSWORD));
		admin.setPermission(Constants.ALL);
		admin.setUserFlag(User.FLAG_ENABLED);
		admin.setCreateTime(new Date());
		// 不设有效期：内置管理员必须长期有效，否则到期后没人能登进来续期
		admin.setExpireTime(null);
		return admin;
	}
}
