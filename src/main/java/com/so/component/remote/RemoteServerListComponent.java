package com.so.component.remote;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.component.CommonComponent;
import com.so.component.ComponentUtil;
import com.so.component.RemoteSSHComponent;
import com.so.component.util.FileUploader;
import com.so.component.util.TabSheetUtil;
import com.so.controller.SshHandler;
import com.so.entity.ConnectionInfo;
import com.so.mapper.ConnectionInfoMapper;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.so.util.SSHClientUtil;
import com.so.util.Util;
import com.vaadin.server.VaadinSession;
import com.vaadin.ui.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 远程服务器列表，读取配置文件中的列表
 * remoteServerList.properties
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class RemoteServerListComponent extends CommonComponent {

	
	private static final Logger log = LoggerFactory.getLogger(RemoteServerListComponent.class);
	public static ConnectionInfo connectionInfo = null;
	private static final long serialVersionUID = 8995914798319911923L;
	private Panel mainPanel;
	private VerticalLayout contentLayout;
	public FileUploader loader;
	@Autowired
	private ConnectionInfoMapper connectionInfoMapper;
	private Button addBtn;
	private MySshWindow win;

	@Override
	public void initLayout() {
		mainPanel = new Panel();
		mainPanel.setHeight("700px");
		setCompositionRoot(mainPanel);
		contentLayout = new VerticalLayout();
		mainPanel.setContent(contentLayout);
		initMainLayout();
	}

	/**
	 * 布局
	 */
	private void initMainLayout() {
		contentLayout.removeAllComponents();
		VerticalLayout serverLayout = new VerticalLayout();
		serverLayout.setSpacing(true);
		serverLayout.setWidth("100%");
		contentLayout.addComponent(serverLayout);
		List<ConnectionInfo> serverListFromDb = connectionInfoMapper.selectList(new QueryWrapper<ConnectionInfo>());
		List<String> remoteServerList = Util.getRemoteServerList();
		for (int i = 0; i < remoteServerList.size(); i++) {
			String[] split = remoteServerList.get(i).split("=");
			ConnectionInfo info = new ConnectionInfo(split[0], split[3],  split[1],  split[2], split[4]);
			serverListFromDb.add(info);
		}
		serverLayout.setHeight((600+serverListFromDb.size()*40)+"px");
		int i = 0;
//		添加刷新列表按钮
		HorizontalLayout refreshL = ComponentFactory.getHorizontalLayout();
		refreshL.setDefaultComponentAlignment(Alignment.MIDDLE_RIGHT);
		refreshL.setHeight("35px");
		Button btn = ComponentFactory.getStandardButton("刷新列表");
		btn.setWidth("100px");
		addBtn = ComponentFactory.getStandardButton("添加机器");
		addBtn.setWidth("100px");
		refreshL.addComponent(btn);
		refreshL.addComponent(addBtn);
		refreshL.setExpandRatio(btn,1);
		btn.addClickListener(e -> {
			this.initMainLayout();
			this.registerHandler();
		});
		serverLayout.addComponent(refreshL);
		for (ConnectionInfo info : serverListFromDb) {
			AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
			if (i%2 != 0){
				abs.addStyleName("remote-server-list-odd");
			}else{
				abs.addStyleName("remote-server-list-even");
			}
			serverLayout.addComponent(abs);
			Label serverLb = new Label(info.getIdHost());
			Button deleteBtn = ComponentFactory.getStandardButton("删除");
			deleteBtn.setData(info);
			deleteBtn.addClickListener(e ->{
				if (!LoginView.checkPermission(Constants.DELETE)){
					Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
					return;
				}
				refreshAndDeleteRecord(info);
			});
			Button manageBtn = ComponentFactory.getStandardButton("应用管理");
			manageBtn.setData(info);
			manageBtn.addClickListener(e ->{
				addRemoteAppTab(info);
			});
			Button sshBtn = ComponentFactory.getStandardButton("SSH管理");
			sshBtn.setData(info);
			sshBtn.addClickListener(e ->{
				addRemoteSSHTab(info);
			});
			Button fileBtn = ComponentFactory.getStandardButton("文件管理");
			fileBtn.setData(info);
			fileBtn.addClickListener(e ->{
				addRemoteFileTab(info);
			});
			Button monitorBtn = ComponentFactory.getStandardButton("指标监控");
			monitorBtn.setData(info);
			monitorBtn.addClickListener(e ->{
				addMonitorTab(info);
			});
			Label descLb = ComponentFactory.getStandardLabel("用户："+info.getIdUser()+"|"+(info.getDesc() == null ? "":info.getDesc()));
			abs.addComponent(serverLb);
			abs.addComponent(manageBtn,"left:155px;");
			abs.addComponent(sshBtn,"left:300px;");
			abs.addComponent(fileBtn,"left:440px;");
			abs.addComponent(monitorBtn,"left:578px;");
			abs.addComponent(deleteBtn,"left:710px;");
			abs.addComponent(descLb,"left:810px;");
			if (i == serverListFromDb.size()-1) {
				serverLayout.setExpandRatio(abs, 1);
			}
			i ++;
		}
	}

	private void refreshAndDeleteRecord(ConnectionInfo data) {
		Window win = new Window("提示");
		win.setHeight("150px");
		win.setWidth("300px");
		win.setModal(true);
		AbsoluteLayout absoluteLayout = ComponentFactory.getAbsoluteLayoutHeightFull();
		
		Label standardLabel = ComponentFactory.getStandardLabel("确认删除吗？");
		Button button = ComponentFactory.getStandardButton("确认");
		button.addClickListener(e ->{
			QueryWrapper<ConnectionInfo> queryWrapper = new QueryWrapper<ConnectionInfo>();
			queryWrapper.eq("id_host", data.getIdHost()).eq("cd_port",data.getCdPort()).eq("id_user",data.getIdUser());
			int delete = connectionInfoMapper.delete(queryWrapper);
			if (delete >0) {
				Notification.show("删除成功", Notification.Type.WARNING_MESSAGE);
			}else {
				Notification.show("该条数据在配置文件，需要手动删除。", Notification.Type.WARNING_MESSAGE);
			}
			win.close();
			initMainLayout();
			initContent();
			registerHandler();
		});
		absoluteLayout.addComponent(standardLabel,"left:35%;top:18%;");
		absoluteLayout.addComponent(button,"right:10%;bottom:10px;");
		win.setContent(absoluteLayout);
		UI.getCurrent().addWindow(win);
	}

	/**
	 * 远程应用管理
	 * @param data
	 */
	private void addRemoteAppTab(ConnectionInfo data) {
		RemoteAppManagement bean = ComponentUtil.applicationContext.getBean(RemoteAppManagement.class);
		bean.setAddr(data);
		bean.setHostName(data.getIdHost());
		bean.initLayout();
		bean.initContent();
		bean.registerHandler();
		TabSheetUtil.getMainTabsheet().addTab(bean,"应用管理-"+data.getIdHost()).setClosable(true);
		TabSheetUtil.getMainTabsheet().setSelectedTab(bean);
	}
	/**
	 * 远程ssh窗口
	 * @param data
	 */
	private void addRemoteSSHTab(ConnectionInfo data) {
		connectionInfo = data;
		RemoteSSHXterm bean = ComponentUtil.applicationContext.getBean(RemoteSSHXterm.class);
//		RemoteSSHComponent bean = ComponentUtil.applicationContext.getBean(RemoteSSHComponent.class);
//		RemoteSSHComponentV2 bean = ComponentUtil.applicationContext.getBean(RemoteSSHComponentV2.class);
		bean.setAddr(data);
		bean.setHostName(data.getIdHost());
		bean.initLayout();
		bean.initContent();
		bean.registerHandler();
		TabSheetUtil.getMainTabsheet().addTab(bean,"SSH-"+data.getIdHost()).setClosable(true);
		TabSheetUtil.getMainTabsheet().setSelectedTab(bean);
	}

	private void addRemoteFileTab(ConnectionInfo data) {
		RemoteFileMgmtComponent bean = ComponentUtil.applicationContext.getBean(RemoteFileMgmtComponent.class);
		bean.setAddr(data);
		bean.setHostName(data.getIdHost());
		bean.initLayout();
		bean.initContent();
		bean.registerHandler();
		String[] split = data.getIdHost().split("\\.");
		TabSheetUtil.getMainTabsheet().addTab(bean,"文件-"+split[split.length-1]+"-"+data.getIdUser()).setClosable(true);
		TabSheetUtil.getMainTabsheet().setSelectedTab(bean);
	}

	private void addMonitorTab(ConnectionInfo data) {
		RemoteMonitorComponent bean = ComponentUtil.applicationContext.getBean(RemoteMonitorComponent.class);
		bean.setCurrentConnectionInfo(data);
		bean.initLayout();
		bean.initContent();
		bean.registerHandler();
		TabSheetUtil.getMainTabsheet().addTab(bean,"监控-"+data.getIdHost()).setClosable(true);
		TabSheetUtil.getMainTabsheet().setSelectedTab(bean);
	}

	@Override
	public void initContent() {
	}

	@Override
	public void registerHandler() {
		addBtn.addClickListener(e ->{
			if (null != win) {
				win.clear();
				UI.getCurrent().addWindow(win);
				win.setModal(true);
			} else {
				win = new MySshWindow("连接信息输入");
				UI.getCurrent().addWindow(win);
				win.setModal(true);
			}
		});
	}

	class MySshWindow extends Window {
		private TextField usernameField;
		private TextField passField;
		private TextField host;
		private TextField port;
		public void clear() {
			host.setValue("");
			usernameField.setValue("");
			passField.setValue("");
		}
		public MySshWindow(String title) {
			super(title); // Set window caption
			center();
			setClosable(true);
			setHeight("450px");
			setWidth("370px");

			VerticalLayout ver = new VerticalLayout();
			ver.setSizeFull();
			FormLayout lay = new FormLayout();
			lay.setHeight("270px");
			host = ComponentFactory.getStandardTtextField("主机：");
			port = new TextField("端口：");
			port.setValue("22");
			usernameField = ComponentFactory.getStandardTtextField("用户名：");

			passField = ComponentFactory.getStandardPassedwordField("密码：");
			TextField desc = ComponentFactory.getStandardTtextField("备注");
			lay.addComponent(host);
			lay.addComponent(port);
			lay.addComponent(usernameField);
			lay.addComponent(passField);
			lay.addComponent(desc);
			ver.addComponent(lay);
			AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
			abs.setHeight("100px");
			ver.addComponent(abs);
			ver.setExpandRatio(abs, 1);
			Button confirm = ComponentFactory.getStandardButton("连接", e -> {
				if (null != usernameField.getValue() && !"".equals(usernameField.getValue()) && null != passField.getValue()
						&& !"".equals(passField.getValue()) && null != host.getValue() && !"".equals(host.getValue())) {
					String hostName = host.getValue();
					Integer sshPort = Integer.valueOf(port.getValue());
					String userName = usernameField.getValue();
					String password = passField.getValue();
					if (StrUtil.isNotEmpty(desc.getValue()) && desc.getValue().length()>30){
						Notification.show("备注最多写30个字", Notification.Type.ERROR_MESSAGE);
						return;
					}
					try {
						if (null != loader.getFile()) {
							//上传了秘钥
							log.info("使用秘钥连接");
							SSHClientUtil client = new SSHClientUtil(hostName,sshPort,loader.getKeypath());
							client.openConnection();
//							session = JschUtil.createSession(hostName, sshPort, userName, loader.getKeypath(), password == null ? null :password.getBytes());
//							channel = JschUtil.openSftp(session, 1800);
						}else {
							SSHClientUtil client = new SSHClientUtil(hostName,sshPort,userName,password);
							client.openConnection();
//							session = JschUtil.createSession(hostName, sshPort, userName, password);
//							channel = JschUtil.openSftp(session, 1800);
						}
					} catch (Exception ex) {
						log.error(ExceptionUtils.getStackTrace(ex));
						Notification.show("创建链接失败，请检查IP、端口、用户名、密码是否有误！", Notification.Type.WARNING_MESSAGE);
						return;
					}

					// 如果连接成功保存用户的配置
//					1saveUserConfig(host.getValue(), Integer.valueOf(port.getValue()), usernameField.getValue(), passField.getValue());
					try {
						connectionInfoMapper.insert(new ConnectionInfo(host.getValue(), port.getValue(), usernameField.getValue(), passField.getValue(), loader.getKeypath(),desc.getValue()));
					} catch (Exception e1) {
						Notification.show("链接信息已经存在！", Notification.Type.WARNING_MESSAGE);
						return;
					}
					this.close();
					Notification.show("链接添加成功", Notification.Type.WARNING_MESSAGE);
					initMainLayout();
					registerHandler();
				} else {
					Notification.show("用户名或密码输入有误", Notification.Type.WARNING_MESSAGE);
				}
			});
			Button cancel = ComponentFactory.getStandardButton("取消", e -> {
				this.close();
			});
			// Create the upload with a caption and set receiver later
			Label lb = new Label("秘钥:");
			lb.setWidth("50px");
			loader = new FileUploader();
			Upload upload = new Upload("上传秘钥",loader);
			upload.setButtonCaption("上传秘钥");
			upload.setHeight("30px");
			upload.addSucceededListener(loader);
			abs.addComponent(lb, "right:295px;top:5px;");
			abs.addComponent(upload, "right:183px;");
			abs.addComponent(confirm, "right:100px;top:60px;");
			abs.addComponent(cancel, "right:10px;top:60px");
			setContent(ver);
		}
	}
}
