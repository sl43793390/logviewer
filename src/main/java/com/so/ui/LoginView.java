package com.so.ui;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.component.ComponentUtil;
import com.so.entity.User;
import com.so.mapper.UserDao;
import com.so.util.Constants;
import com.so.util.DbInitializer;
import com.so.util.Util;
import com.vaadin.event.ShortcutAction.KeyCode;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener.ViewChangeEvent;
import com.vaadin.server.VaadinSession;
import com.vaadin.spring.annotation.SpringView;
import com.vaadin.spring.annotation.UIScope;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.CssLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.PasswordField;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;

import cn.hutool.core.util.StrUtil;

/**
 * 登录页。
 * <p>
 * 版式是「左侧品牌介绍 + 右侧登录卡片」的整屏分栏，对应的样式在主题文件
 * {@code VAADIN/themes/mytheme/styles.css} 末尾的「登录页样式」段落里，
 * 类名统一以 {@code login-} 开头，改样式时两边一起看。
 * <p>
 * 左右分栏用 {@link CssLayout} 承载而不是 HorizontalLayout：CssLayout 不会给
 * 每个子组件再套一层 {@code v-slot} 容器，因此可以直接用 flex 控制宽度比例，
 * 窄屏时也能干净地把左侧品牌区隐藏掉。
 */
@UIScope
@SpringView(name = "loginView")
public class LoginView extends VerticalLayout implements View {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = LoggerFactory.getLogger(LoginView.class);

	private TextField userFld;
	private PasswordField pwdFld;
	private Button loginBtn;
	/** 登录失败提示，常驻在表单里，不会被自动关闭的 Notification 顶掉 */
	private Label errorLabel;

	@Autowired
	private UserDao userDao;

	public LoginView() {
		super();
		addStyleName("login-root");
		// 这里不用 setSizeFull()：它会给根组件写死 height:100%，而导航容器 v-ui
		// 本身高度是 auto，百分比算下来会塌成内容高度。整页高度统一交给样式表
		// 里的 100vh 控制，窄屏、矮屏的表现也更好调。
		setWidth("100%");
		setSpacing(false);
		setMargin(false);
		initUi();
	};

	protected void initUi() {
		removeAllComponents();
		CssLayout body = new CssLayout();
		body.addStyleName("login-body");
		addComponent(body);
		body.addComponent(buildBrandPanel());
		body.addComponent(buildLoginPanel());
	}

	/**
	 * 左侧品牌区：标题 + 功能点 + 版本信息
	 */
	private VerticalLayout buildBrandPanel() {
		VerticalLayout brand = new VerticalLayout();
		brand.addStyleName("login-brand");
		brand.setSpacing(false);
		brand.setMargin(false);

		Label logo = new Label(">_");
		logo.addStyleName("login-logo");
		Label brandName = new Label("LogViewer");
		brandName.addStyleName("login-brand-name");
		Label brandSub = new Label("服务器日志与运维管理平台");
		brandSub.addStyleName("login-brand-sub");
		VerticalLayout nameBox = new VerticalLayout();
		nameBox.setSpacing(false);
		nameBox.setMargin(false);
		nameBox.addComponents(brandName, brandSub);

		// 用 CssLayout 而不是 HorizontalLayout 承载这一行：CssLayout 不会给子组件
		// 套 v-slot，避免受 Valo 里 .v-slot 的 nowrap、v-align-* 对齐类影响，
		// 垂直居中直接交给 CSS 的 flex 处理
		CssLayout head = new CssLayout();
		head.addStyleName("login-brand-head");
		head.addComponents(logo, nameBox);
		brand.addComponent(head);

		Label title = new Label("远程日志查看，一处搞定");
		title.addStyleName("login-brand-title");
		brand.addComponent(title);

		Label desc = new Label("多台服务器的日志、应用和进程集中管理，浏览器打开就能用，不用再逐台登录跳板机。");
		desc.addStyleName("login-brand-desc");
		brand.addComponent(desc);

		VerticalLayout features = new VerticalLayout();
		features.addStyleName("login-feature-list");
		features.setSpacing(false);
		features.setMargin(false);
		addFeature(features, "本地与远程日志在线检索，大文件分页打开");
		addFeature(features, "Jar、Tomcat、通用项目集中管理，一键启停");
		addFeature(features, "Web SSH 终端与文件上传下载，免装客户端");
		addFeature(features, "CPU、内存、磁盘使用率实时监控");
		brand.addComponent(features);

		Label foot = new Label("LogViewer v3.1.0 · Vaadin 8 + Spring Boot 2.7");
		foot.addStyleName("login-brand-foot");
		brand.addComponent(foot);
		return brand;
	}

	/**
	 * 品牌区的一条功能点
	 */
	private void addFeature(VerticalLayout target, String text) {
		Label dot = new Label("✓");
		dot.addStyleName("login-feature-dot");
		Label content = new Label(text);
		content.addStyleName("login-feature-text");

		CssLayout row = new CssLayout();
		row.addStyleName("login-feature-row");
		row.addComponents(dot, content);
		target.addComponent(row);
	}

	/**
	 * 右侧登录区：居中的登录卡片
	 */
	private VerticalLayout buildLoginPanel() {
		VerticalLayout panel = new VerticalLayout();
		panel.addStyleName("login-panel");
		panel.setSpacing(false);
		panel.setMargin(false);

		Label cardTitle = new Label("欢迎回来");
		cardTitle.addStyleName("login-card-title");
		Label cardSub = new Label("请使用系统分配的账号登录");
		cardSub.addStyleName("login-card-sub");

		errorLabel = new Label();
		errorLabel.addStyleName("login-error");
		errorLabel.setVisible(false);

		userFld = ComponentFactory.getStandardTtextField("用户名");
		userFld.addStyleName("login-field");
		userFld.addStyleName("login-field-user");
		userFld.setPlaceholder("请输入用户名");
		userFld.setWidth("100%");
		userFld.setHeight("44px");

		pwdFld = ComponentFactory.getStandardPassedwordField("密码");
		pwdFld.addStyleName("login-field");
		pwdFld.addStyleName("login-field-pwd");
		pwdFld.setPlaceholder("请输入密码");
		pwdFld.setWidth("100%");
		pwdFld.setHeight("44px");
		pwdFld.setValue("");

		loginBtn = new Button("登录");
		loginBtn.addStyleName("login-submit");
		loginBtn.setWidth("100%");
		loginBtn.setHeight("46px");
		loginBtn.setClickShortcut(KeyCode.ENTER, null);
		loginBtn.addClickListener(new loginListener());

		Label cardFoot = new Label("忘记密码请联系系统管理员");
		cardFoot.addStyleName("login-card-foot");

		VerticalLayout card = new VerticalLayout();
		card.addStyleName("login-card");
		card.setSpacing(false);
		card.setMargin(false);
		card.addComponents(cardTitle, cardSub, errorLabel, userFld, pwdFld, loginBtn, cardFoot);

		panel.addComponent(card);
		return panel;
	}

	private void showError(String message) {
		errorLabel.setValue(message);
		errorLabel.setVisible(true);
		userFld.addStyleName("login-field-error");
		pwdFld.addStyleName("login-field-error");
	}

	private void hideError() {
		errorLabel.setVisible(false);
		userFld.removeStyleName("login-field-error");
		pwdFld.removeStyleName("login-field-error");
	}

	protected void refreshUi() {
		initUi();
	}

	class loginListener implements Button.ClickListener {
		private static final long serialVersionUID = 1L;

		@Override
		public void buttonClick(ClickEvent event) {
			// 从别处复制粘贴过来的账号常带首尾空格，这里统一去掉再比对
			String userName = StrUtil.trim(userFld.getValue());
			String pwd = pwdFld.getValue();
			if (StrUtil.isBlank(userName)) {
				showError("请输入用户名");
				userFld.focus();
				return;
			}
			if (StrUtil.isBlank(pwd)) {
				showError("请输入密码");
				pwdFld.focus();
				return;
			}
			User user = userDao.selectById(userName);
			if (null != user && null != user.getPassword()
					&& user.getPassword().equals(Util.getSm3DigestStr(pwd))) {
				// 密码正确之后才提示"被禁用 / 已过期"：密码错误的场景不该暴露账号状态。
				// 这段校验是 user_flag 与 expire_time 真正生效的地方，少了它，
				// 用户管理页上的"禁用"只是个不生效的标签。
				String reason = user.unavailableReason();
				if (null != reason) {
					showError(reason);
					pwdFld.setValue("");
					logger.info("用户{}被拒绝登录：{}", userName, reason);
					return;
				}
				hideError();
				VaadinSession.getCurrent().setAttribute("userName", userName);
				VaadinSession.getCurrent().setAttribute("user", user);
				UI.getCurrent().getNavigator().navigateTo(MyUI.MAIN_VIEW);
				logger.info("用户{}登录成功", userName);
			} else {
				showError("用户名或密码错误，请重新输入");
				pwdFld.setValue("");
				pwdFld.focus();
				logger.info("用户{}登录失败", userName);
			}
		}
	}

	@Override
	public void enter(ViewChangeEvent event) {
		// focus the username field when user arrives to the login view
		userFld.setValue("");
		pwdFld.setValue("");
		hideError();
		userFld.focus();
		//添加初始化sql逻辑：如果第一次则全部执行，如果不是则跳过
		DataSource dataSource = ComponentUtil.applicationContext.getBean(DataSource.class);
		try {
			Integer selectCount = userDao.selectCount(new QueryWrapper<User>());
			if (null == selectCount || selectCount <= 0) {
				init(dataSource);
			}
		} catch (Exception e) {
			logger.warn("首次启动，进行数据库初始化。。。");
			init(dataSource);
		}
		// 无论库是刚建出来的还是沿用旧库，都保证内置管理员存在且能登录。
		// 这一步是"进得来"的最后保险：不再依赖运行目录下的 users.properties，
		// 那个文件丢了、没拷、编码错了都不会再把系统锁在门外。
		DbInitializer.ensureAdminUser(dataSource, userDao);
		// 补齐 server.sh（运行目录 + bin 目录），以 WAR 方式部署时 main 不会执行
		try {
			Util.ensureServerScript();
		} catch (Exception e) {
			logger.warn("释放 server.sh 失败：{}", e.getMessage());
		}
	}

	/**
	 * 根据初始化sql文件和datasource执行指定的sql。
	 * @param dataSource
	 */
	 public void init(DataSource dataSource) {
	        // 项目启动初始化基本数据
	        logger.info("数据初始化开始: ");
	        // 通过直接读取sql文件执行
	        ClassPathResource resources = new ClassPathResource("demo.sql");
	        if (!resources.exists()) {
	        	logger.error("classpath 下未找到 demo.sql，跳过数据库初始化");
	        	return;
	        }
	        ResourceDatabasePopulator resourceDatabasePopulator = new ResourceDatabasePopulator();
	        resourceDatabasePopulator.addScripts(resources);
	        resourceDatabasePopulator.execute(dataSource);
	        logger.info("数据初始化结束: ");
	    }

		public static boolean checkPermission(String res){
			User user = (User)VaadinSession.getCurrent().getAttribute("user");
			if (null == user || null == user.getPermission()) {
				return false;
			}
			String permission = user.getPermission();
			// 权限串以逗号结尾，用 contains 判断会把 "ADD" 误匹配成 "ADDX"，
			// 这里按逗号切分后做精确比较
			for (String item : permission.split(",")) {
				String trimmed = item.trim();
				if (Constants.ALL.equals(trimmed) || res.equals(trimmed)) {
					return true;
				}
			}
			return false;
		}
}
