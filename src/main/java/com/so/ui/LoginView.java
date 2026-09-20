package com.so.ui;

import javax.sql.DataSource;

import com.so.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.component.ComponentUtil;
import com.so.entity.User;
import com.so.mapper.UserDao;
import com.so.util.Util;
import com.vaadin.event.ShortcutAction.KeyCode;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener.ViewChangeEvent;
import com.vaadin.server.VaadinSession;
import com.vaadin.spring.annotation.SpringView;
import com.vaadin.spring.annotation.UIScope;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.PasswordField;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;

import cn.hutool.core.util.StrUtil;

@UIScope
@SpringView(name = "loginView")
public class LoginView extends VerticalLayout implements View {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = LoggerFactory.getLogger(LoginView.class);

	private TextField userFld;
	private PasswordField pwdFld;
	private Button loginBtn;
	@Autowired
	private UserDao userDao;

	public LoginView() {
		super();
		addStyleName("login-new-general");
		setSizeFull();
		initUi();
	};

	protected void initUi() {
		removeAllComponents();
		VerticalLayout contentLayout = new VerticalLayout();
		contentLayout.addStyleName("logview-loginVayout");
		contentLayout.setWidth("100%");
		addComponent(contentLayout);
		setComponentAlignment(contentLayout, Alignment.MIDDLE_CENTER);
		initLayoutFirstPart(contentLayout);
		initLayoutSecondPart(contentLayout);
		initLayoutThirdPart(contentLayout);
	}

	private void initLayoutFirstPart(VerticalLayout contentLayout) {
		HorizontalLayout firstLayout = new HorizontalLayout();
		firstLayout.setWidth("100%");
		firstLayout.setHeight("69px");

		HorizontalLayout linkLayout = new HorizontalLayout();
//		linkLayout.setWidth("558px");
		linkLayout.setHeight("69px");
		linkLayout.addStyleName("login-new-link");
		firstLayout.addComponent(linkLayout);
		firstLayout.setComponentAlignment(linkLayout, Alignment.MIDDLE_CENTER);
//		Label blankLabel = new Label();
//		linkLayout.addComponent(blankLabel);
//		linkLayout.setExpandRatio(blankLabel, 1.0f);
		linkLayout.setSpacing(true);
		Label standardTitle = ComponentFactory.getStandardTitle("欢迎使用LogViewer");
		standardTitle.addStyleName("login-title");
		linkLayout.addComponent(standardTitle);

		contentLayout.addComponent(firstLayout);
	}

	private void initLayoutSecondPart(VerticalLayout contentLayout) {
		VerticalLayout secondLayout = new VerticalLayout();
		secondLayout.setWidth("100%");
		secondLayout.addStyleName("login-new-general-content-container");
		contentLayout.addComponent(secondLayout);

		initLayoutForm(secondLayout);
	}

	private void initLayoutForm(VerticalLayout secondLayout) {
		VerticalLayout formContainerLayout = new VerticalLayout();
		formContainerLayout.setWidth("320px");
		secondLayout.addComponent(formContainerLayout);
		secondLayout.setComponentAlignment(formContainerLayout, Alignment.MIDDLE_CENTER);

		userFld = ComponentFactory.getStandardTtextField("用户名");
//		userFld = new TextField("用户名");
		userFld.addStyleName("login-userfield");
		userFld.setWidth("260px");
		userFld.setHeight("35px");

		// Create the password input field
		pwdFld = ComponentFactory.getStandardPassedwordField("密码");
//		pwdFld = new PasswordField("密码");
		pwdFld.addStyleName("login-pwdfield");
		pwdFld.setWidth("260px");
		pwdFld.setHeight("35px");
		pwdFld.setValue("");
		

		// Create login button
		loginBtn = new Button("登录");
		loginBtn.setWidth("260px");
		loginBtn.setHeight("35px");
		loginBtn.setClickShortcut(KeyCode.ENTER, null);
		loginBtn.addClickListener(new loginListener());

		FormLayout fields = new FormLayout();
		fields.addComponent(userFld);
		fields.addComponent(pwdFld);
		fields.addComponent(loginBtn);
		fields.setWidth("320px");
		formContainerLayout.addComponent(fields);
	}

	private void initLayoutThirdPart(VerticalLayout contentLayout) {
		HorizontalLayout thirdLayout = new HorizontalLayout();
		thirdLayout.setWidth("100%");
		thirdLayout.setHeight("69px");
		thirdLayout.addStyleName("login-new-general-content-link");
		contentLayout.addComponent(thirdLayout);
	}

	protected void refreshUi() {
		initUi();
	}

	class loginListener implements Button.ClickListener {
		private static final long serialVersionUID = 1L;

		@Override
		public void buttonClick(ClickEvent event) {
			String userName = userFld.getValue();
			String pwd = pwdFld.getValue();
			if (StrUtil.isBlank(userName) || StrUtil.isBlank(pwd)) {
				Notification.show("用户名或密码不能为空", Notification.Type.WARNING_MESSAGE);
				return;
			}
			User user = userDao.selectById(userName);
			if (null != user && null != user.getPassword()
					&& user.getPassword().equals(Util.getSm3DigestStr(pwd))) {
				VaadinSession.getCurrent().setAttribute("userName", userName);
				VaadinSession.getCurrent().setAttribute("user", user);
				UI.getCurrent().getNavigator().navigateTo("logCheckView");
				logger.info("用户{}登录成功", userName);
			} else {
				Notification.show("用户名或密码错误", Notification.Type.WARNING_MESSAGE);
			}
		}
	}

	@Override
	public void enter(ViewChangeEvent event) {
		// focus the username field when user arrives to the login view
		userFld.setValue("");
		pwdFld.setValue("");
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
