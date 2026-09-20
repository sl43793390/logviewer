package com.so.ui;

import com.so.component.remote.RemoteLoginComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.so.component.CommonComponent;
import com.so.component.ComponentUtil;
import com.so.component.LogSearchComponent;
import com.so.component.remote.RemoteLogSearchComponent;
import com.so.component.RemoteSSHComponent;
import com.so.component.management.UserGuideComponent;
import com.so.component.remote.RemoteAppManagement;
import com.so.component.util.TabSheetUtil;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewChangeListener.ViewChangeEvent;
import com.vaadin.server.Page;
import com.vaadin.server.ThemeResource;
import com.vaadin.server.VaadinSession;
import com.vaadin.spring.annotation.SpringView;
import com.vaadin.spring.annotation.UIScope;
import com.vaadin.ui.AbsoluteLayout;
import com.vaadin.ui.Button;
import com.vaadin.ui.Component;
import com.vaadin.ui.Image;
import com.vaadin.ui.Label;
import com.vaadin.ui.MenuBar;
import com.vaadin.ui.MenuBar.Command;
import com.vaadin.ui.MenuBar.MenuItem;
import com.vaadin.ui.TabSheet;
import com.vaadin.ui.VerticalLayout;



@UIScope
@SpringView(name = "logCheckView")
public class LogCheckView extends VerticalLayout implements View {

	private static final long serialVersionUID = -3063743016291386129L;
	private static final Logger log = LoggerFactory.getLogger(LogCheckView.class);
	private TabSheet mainTabsheet;
	private MenuBar menuBar;

	/**
	 *<li>做成tabsheet类型 首次启动判断是否存在配置文件，不存在则创建：config.properties 存在则读取保存的路径，
	 *<li>选择路径后，右侧展示该路径下的所有log后缀的文件（后缀可配置）
	 *<li> 选择具体某个文件后开始分页读取，默认每页200条日志（可配置）,显示文件大小**、创建日期等信息 读取日志编码默认UTF-8，（可配置）
	 *<li> 日志预览功能未处理文件较大的问题，可以考虑只能预览日志最后100页，如需查看所有下载即可；
	 *<li> +TODO ,编码自动判断功能
	 *<li>+选择日志级别功能
	 *<li> +添加用户功能，保存用户对应配置文件功能
	 * <li>++能够登陆其他机器通过jsch，进行相关操作
	 * 
	 */
	@Autowired
	private LogSearchComponent logSearchComponent;
	@Autowired
	private UserGuideComponent userGuideComponent;
	@Override
	public void enter(ViewChangeEvent event) {
		addHeader();
		addMenuItem();
		addMainTabsheet();
		this.setHeight("910px");
	}

	private void addMenuItem() {
		AbsoluteLayout menuLayout = ComponentFactory.getAbsoluteLayout();
		menuLayout.setHeight("38px");
		addComponent(menuLayout);
		menuBar = new MenuBar();
		menuBar.addStyleName("menubar-style");
		menuBar.setWidth("100%");
		menuLayout.addComponent(menuBar);
		MenuItem localMgmt = menuBar.addItem("本地应用管理");
		MenuItem remoteMgmt = menuBar.addItem("远程应用管理");
//		MenuItem cornItem = menuBar.addItem("定时任务");
		MenuItem dockerItem = menuBar.addItem("Docker管理");
		MenuItem secureItem = menuBar.addItem("安全管理");
		MenuItem toolItem = menuBar.addItem("其他工具");
		MenuItem userItem = menuBar.addItem("用户管理");
		//子菜单
		addSubMenu(localMgmt,"LogSearchComponent","本地日志搜索");
		addSubMenu(localMgmt,"management.LocalFileMgmtComponent","本地文件管理");
		addSubMenu(localMgmt,"management.JarMgmtComponent","jar项目管理");
		addSubMenu(localMgmt,"management.TomcatListComponent","Tomcat管理");
		addSubMenu(localMgmt,"management.CommonProjecttMgmtLocal","通用项目管理");

		addSubMenu(remoteMgmt,"remote.RemoteLoginComponent","远程日志搜索");
		addSubMenu(remoteMgmt,"remote.RemoteServerListComponent","免登录服务器列表");
//		addSubMenu(publishItem,"management.TestTomcatManage","测试Tomcat启停");
//		addSubMenu(publishItem,"management.UserGuideComponent","使用说明");
//		addSubMenu(cornItem,"CrontabComponent","定时任务配置");
		addSubMenu(toolItem,"EncryptionComponent","加密工具");
		if (ComponentUtil.getCurrentUser().getUserId().equals("admin")){
//			addSubMenu(toolItem,"management.GridUseDemoTest","组件样式案例");
//			addSubMenu(toolItem,"management.ChartsDemo","chart示例");
		}
		addSubMenu(userItem,"UserManagementComponent","用户管理");
	}

	private void addSubMenu(MenuItem item,String calssName,String title) {
		item.addItem(title, new Command() {
			
			private static final long serialVersionUID = 6892561512969810816L;

			@Override
			public void menuSelected(MenuItem selectedItem) {
				// 同一个功能重复点击时直接切回已有 tab，避免开出多个同内容标签页
				if (TabSheetUtil.checkComponent(title)) {
					TabSheetUtil.selectTargetTab(title);
					return;
				}
				CommonComponent createComponentUseClassName = ComponentUtil.createComponentUseClassName(calssName);
				createComponentUseClassName.initLayout();
				createComponentUseClassName.initContent();
				createComponentUseClassName.registerHandler();
				mainTabsheet.addTab(createComponentUseClassName,title).setClosable(true);
				mainTabsheet.setSelectedTab(createComponentUseClassName);
			}
		});
	}
	
	private void addMainTabsheet() {
		mainTabsheet = new TabSheet();
		addComponent(mainTabsheet);
		userGuideComponent.initLayout();
		userGuideComponent.initContent();
		userGuideComponent.registerHandler();

		logSearchComponent.initLayout();
		logSearchComponent.initContent();
		logSearchComponent.registerHandler();
		mainTabsheet.addTab(userGuideComponent, "使用说明");
		mainTabsheet.addTab(logSearchComponent, "本地日志搜索");
		mainTabsheet.setSelectedTab(logSearchComponent);
		setExpandRatio(mainTabsheet, 1);
		//当关闭tab时关闭远程连接会话
		mainTabsheet.addComponentDetachListener(e ->{
			Component detachedComponent = e.getDetachedComponent();
			if (detachedComponent instanceof RemoteLoginComponent) {
				RemoteLoginComponent com = (RemoteLoginComponent)detachedComponent;
				try {
					com.closeChannel();
				} catch (Exception e1) {
					e1.printStackTrace();
				}
			}
			if (detachedComponent instanceof RemoteLogSearchComponent) {
				RemoteLogSearchComponent com = (RemoteLogSearchComponent)detachedComponent;
				try {
					com.closeChannel();
				} catch (Exception e1) {
					e1.printStackTrace();
				}
			}
			if (detachedComponent instanceof RemoteAppManagement) {
				RemoteAppManagement com = (RemoteAppManagement)detachedComponent;
				try {
					com.closeChannel();
				} catch (Exception e1) {
					e1.printStackTrace();
				}
			}
		});
	}


	private void addHeader() {
		AbsoluteLayout cssl = ComponentFactory.getAbsoluteLayout();
		cssl.setWidth("100%");
		cssl.addStyleName("mainview-cssl");
		Button exitBtn = ComponentFactory.getStandardButton("退出",e->{
			VaadinSession.getCurrent().getSession().invalidate();
			Page.getCurrent().setLocation(Page.getCurrent().getLocation().toString().split("#!")[0]);
		});
		exitBtn.addStyleName("logcheckView-exitbtn");
		Label userNameLb = ComponentFactory.getStandardLabel(VaadinSession.getCurrent().getAttribute("userName").toString());
		userNameLb.setWidth("90px");
		Image img = new Image("", new ThemeResource("img/user.png"));
		cssl.addComponent(img, "right:180px;top:10px;");
		cssl.addComponent(userNameLb, "right:75px;top:7px;");
		cssl.addComponent(exitBtn, "right:10px;top:8px;");
		Label lb2 = ComponentFactory.getStandardLabel("欢迎...");
		cssl.addComponent(lb2, "left:10px;top:5px;");
		addComponent(cssl);
	}

	public LogCheckView() {
		super();
		addStyleName("login-new-general");
		setSizeFull();
	}

	public TabSheet getMainTabsheet() {
		return mainTabsheet;
	}

	public void setMainTabsheet(TabSheet mainTabsheet) {
		this.mainTabsheet = mainTabsheet;
	};

}
