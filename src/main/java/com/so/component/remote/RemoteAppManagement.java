package com.so.component.remote;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.so.component.CommonComponent;
import com.so.component.ComponentUtil;
import com.so.component.util.TabSheetUtil;
import com.so.entity.ConnectionInfo;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TabSheet;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.ssh.JschUtil;

/**
 * 远程应用管理页面，将tomcat管理、jar包管理、NGINX等应用管理放到一个tabsheet里面
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class RemoteAppManagement extends CommonComponent {

	private static final long serialVersionUID = -5574943080620362841L;

	private static final Logger log = LoggerFactory.getLogger(RemoteAppManagement.class);

	private Panel mainPanel;
	private TabSheet tabsheet;
	private Session session;
	private ChannelSftp channel;
	private ConnectionInfo addr;
	private String hostName;
	@Override
	public void initLayout() {
		mainPanel = new Panel();
		setCompositionRoot(mainPanel);
		tabsheet = new TabSheet();
		mainPanel.setContent(tabsheet);
		readyToConnect();
		if (null == session) {
			// 连接建不起来时不要继续建 Tab，否则子组件拿到 null session 会在各种地方 NPE
			tabsheet.addComponent(new Label("未能连接到 " + hostName + "，请检查连接配置后重试"));
			return;
		}
		initMainLayout();
	}

	/**
	 * 布局
	 */
	private void initMainLayout() {
		String host = addr.getIdHost();
		RemoteLogSearchComponent logSearchComponent = ComponentUtil.applicationContext.getBean(RemoteLogSearchComponent.class);
		logSearchComponent.setJschSession(session);
		logSearchComponent.setAddr(addr);
		logSearchComponent.initLayout();
		logSearchComponent.initContent();
		logSearchComponent.registerHandler();
		tabsheet.addTab(logSearchComponent,"　日志搜索-"+host).setClosable(false);
		tabsheet.setSelectedTab(logSearchComponent);

		RemoteJarMgmtComponent bean = ComponentUtil.applicationContext.getBean(RemoteJarMgmtComponent.class);
		bean.setJschSession(session);
		bean.setAddr(addr);
		bean.initLayout();
		bean.initContent();
		bean.registerHandler();
		tabsheet.addTab(bean,"Jar项目管理-"+host).setClosable(false);
		tabsheet.setSelectedTab(bean);
		//tomat 管理页面
		RemoteTomcatMgmtComponent tomcat = ComponentUtil.applicationContext.getBean(RemoteTomcatMgmtComponent.class);
		tomcat.setJschSession(session);
		tomcat.setAddr(addr);
		tomcat.initLayout();
		tomcat.initContent();
		tomcat.registerHandler();
		tabsheet.addTab(tomcat,"Tomcat管理-"+host).setClosable(false);
//		nginx及其它管理页面
		CommonProjecttMgmtComponent common = ComponentUtil.applicationContext.getBean(CommonProjecttMgmtComponent.class);
		common.setJschSession(session);
		common.setAddr(addr);
		common.initLayout();
		common.initContent();
		common.registerHandler();
		tabsheet.addTab(common,"通用项目管理-"+host).setClosable(false);
	}

	@Override
	public void detach() {
		super.detach();
		// session 是本组件创建的，tab 关闭时必须断开。
		// 原来 closeChannel() 全项目没有任何调用点，每开一次「应用管理」都会漏一条 SSH 连接
		try {
			closeChannel();
		} catch (Exception e) {
			log.error("关闭远程连接失败", e);
		}
	}

	@Override
	public void initContent() {
		// TODO Auto-generated method stub

	}

	@Override
	public void registerHandler() {
		// TODO Auto-generated method stub
	}
	private void readyToConnect() {
		try {
			if (null == addr) {
				throw new IllegalStateException("连接信息为空");
			}
			// cdKeyPath 可能是空串而不是 null，用 == null 判断会把空串当成"配了秘钥"
			if (StrUtil.isBlank(addr.getCdKeyPath())) {
				//无秘钥连接
				session = JschUtil.createSession(hostName, Integer.parseInt(addr.getCdPort()), addr.getIdUser(), addr.getCdPassword());
			} else {
				//秘钥连接
				session = JschUtil.createSession(hostName, Integer.parseInt(addr.getCdPort()), addr.getIdUser(),addr.getCdKeyPath(),  addr.getCdPassword() == null ?null :addr.getCdPassword().getBytes());
			}
		} catch (Exception e) {
			// 原来只 catch NumberFormatException，认证失败/主机不可达等都会直接冒到 UI 层
			log.error("连接远程主机 {} 失败：{}", hostName, e.getMessage(), e);
			session = null;
			Notification.show("连接失败请检查配置：" + e.getMessage(), Notification.Type.ERROR_MESSAGE);
		}
	}
	
	/**
	 * 断开SFTP Channel、Session连接
	 * 
	 * @throws Exception
	 */
	public void closeChannel() throws Exception {
		if (channel != null) {
			channel.disconnect();
		}
		if (session != null) {
			session.disconnect();
		}
		log.info("disconnected SFTP successfully!");
	}

	public void setAddr(ConnectionInfo addr) {
		this.addr = addr;
	}

	public String getHostName() {
		return hostName;
	}

	public void setHostName(String hostName) {
		this.hostName = hostName;
	}
	
}
