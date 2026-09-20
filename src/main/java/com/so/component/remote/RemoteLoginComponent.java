package com.so.component.remote;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;

import com.so.component.CommonComponent;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.so.ui.HorizontalGroupLayout;
import com.so.ui.ComponentFactory;
import com.so.util.Util;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.so.component.util.FileUploader;
import com.so.entity.ConnectionInfo;
import com.so.entity.LogPath;
import com.so.entity.PathEntityInfo;
import com.so.mapper.ConnectionInfoMapper;
import com.so.mapper.LogPathMapper;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import com.vaadin.server.StreamResource.StreamSource;
import com.vaadin.ui.AbsoluteLayout;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.Upload;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.extra.ssh.JschUtil;

/**
 * 远程登录搜索日志页面，可以新增服务器连接配置。
 * 
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class RemoteLoginComponent extends CommonComponent {

	private static final Logger log = LoggerFactory.getLogger(RemoteLoginComponent.class);

	private static final long serialVersionUID = 1578091801743458376L;

	private Panel mainPanel;

	private ComboBox<String> pathHisCombo;
	private ComboBox<String> fileSuffixCombo;
	private Button searchBtn;
	private VerticalLayout contentLayout;

	private TextField pathField;

	private HashSet<String> items = new HashSet<String>();

	private ArrayList<PathEntityInfo> searchFileList;

	public TextField host;

	private Session session;
	private ChannelSftp channel;

	private Button reConnectBtn;

	private MySshWindow win;

	private ComboBox<String> connectHisCombo;
	private String hostName;
	private Integer sshPort;
	private String userName;
	private String password;
	public File file;

	private VerticalLayout resultLayout;

	public FileUploader loader;

	private ComboBox<String> containsCombo;
	@Autowired
	private LogPathMapper logPathMapper;
	@Autowired
	private ConnectionInfoMapper connectionInfoMapper;

	private Button addConnectBtn;

	@Override
	public void initLayout() {
		mainPanel = new Panel();
		contentLayout = new VerticalLayout();
		setCompositionRoot(mainPanel);
		mainPanel.setContent(contentLayout);
		contentLayout.setWidth("100%");
		contentLayout.setHeight("700px");
		initMainLayout();
		initHisInputLayout();
		initLogSearchResultLayout();
	}

	/**
	 * 布局
	 */
	private void initMainLayout() {
		// AbsoluteLayout abs = RquestComponent.getAbsoluteLayout();
		HorizontalGroupLayout abs = new HorizontalGroupLayout(new Integer[]  { 1, 3, 1, 2, 1, 2, 2});
		abs.setHeight("41px");
		contentLayout.addComponent(abs);
		Label pathLb = new Label("输入日志路径：");
		abs.getAbsoluteLayouts().get(0).addComponent(pathLb);

		pathField = ComponentFactory.getStandardTtextField();
		pathField.setWidth("370px");
		pathField.setDescription("请输入文件所在路径和后缀后点击搜索");
		abs.getAbsoluteLayouts().get(1).addComponent(pathField,"left:15px;");

		// 文件后缀默认log
		Label suffixLogLb = new Label("选择后缀：");
		abs.getAbsoluteLayouts().get(2).addComponent(suffixLogLb);
		fileSuffixCombo = ComponentFactory.getStandardComboBox();
		fileSuffixCombo.setEmptySelectionAllowed(false);
		fileSuffixCombo.setItems(Util.getFileSuffix());
		fileSuffixCombo.setValue("log");
		abs.getAbsoluteLayouts().get(3).addComponent(fileSuffixCombo);
		// 包含模式
		Label containsLb = new Label("包含模式：");
		abs.getAbsoluteLayouts().get(4).addComponent(containsLb);
		containsCombo = ComponentFactory.getStandardComboBox();
		containsCombo.setDescription("如果有catalina.log.20200717这种格式可能用后缀模式无法匹配到");
		containsCombo.setEmptySelectionAllowed(false);
		containsCombo.setItems("是", "否");
		containsCombo.setValue("否");
		abs.getAbsoluteLayouts().get(5).addComponent(containsCombo);
		
		searchBtn = ComponentFactory.getStandardButton("搜索日志");
		abs.getAbsoluteLayouts().get(6).addComponent(searchBtn);

	}

	/**
	 * 展示历史输入路径
	 */
	private void initHisInputLayout() {
		HorizontalGroupLayout abshis = new HorizontalGroupLayout(new Integer[] { 1, 3, 1, 2, 1, 2, 2});
		abshis.setHeight("41px");
		contentLayout.addComponent(abshis);
		Label pathLb = new Label("路径搜索历史：");
		abshis.getAbsoluteLayouts().get(0).addComponent(pathLb);

		pathHisCombo = ComponentFactory.getStandardComboBox();
		pathHisCombo.setWidth("370px");
		abshis.getAbsoluteLayouts().get(1).addComponent(pathHisCombo,"left:15px;");

		Label conLb = new Label("连接历史：");
		abshis.getAbsoluteLayouts().get(2).addComponent(conLb);

		connectHisCombo = ComponentFactory.getStandardComboBox();
		abshis.getAbsoluteLayouts().get(3).addComponent(connectHisCombo);

		reConnectBtn = ComponentFactory.getStandardButton("连接");
		abshis.getAbsoluteLayouts().get(4).addComponent(reConnectBtn);
		addConnectBtn = ComponentFactory.getStandardButton("新建连接");
		abshis.getAbsoluteLayouts().get(5).addComponent(addConnectBtn);

		// 其他功能
		Label des = new Label("搜索结果列表");
		contentLayout.addComponent(des);
		Label line = new Label();
		line.setHeight("2px");
		line.addStyleName("split_line");
		contentLayout.addComponent(line);
		
		HorizontalLayout layoutTitle = ComponentFactory.getHorizontalLayout();
		layoutTitle.setWidth("58%");
		layoutTitle.addStyleName("remote-logsearch-title-layout");
		Label lb = new Label("文件名");
//		lb.addStyleName("remote-log-search-lb");
		Label datelb = new Label("修改日期");
//		datelb.addStyleName("remote-log-search-lb");
		Label sizelb = new Label("文件大小");
//		sizelb.addStyleName("remote-log-search-lb");
		Label downloadlb = new Label("文件下载");
//		downloadlb.addStyleName("remote-log-search-lb");
		layoutTitle.addComponent(lb);
		layoutTitle.addComponent(datelb);
		layoutTitle.addComponent(sizelb);
		layoutTitle.addComponent(downloadlb);
		contentLayout.addComponent(layoutTitle);
	}

	private void initLogSearchResultLayout() {
		// 显示搜索的日志文件列表
		Panel resultPanel = new Panel();
		resultPanel.addStyleName("remote-log-search-panel");
		resultLayout = new VerticalLayout();
		resultPanel.setContent(resultLayout);
		contentLayout.addComponent(resultPanel);
		contentLayout.setExpandRatio(resultPanel, 1);
	}
	@Override
	public void initContent() {
		getConfigForHis();
	}
	private void getConfigForHis() {
		QueryWrapper<LogPath> queryWrapper = new QueryWrapper<LogPath>();
		// hostName 在点"连接"之前是 null，直接 eq 会拼出 id_loghost = null 的无效条件
		if (null != hostName) {
			queryWrapper.eq("id_loghost", hostName);
		}
		List<LogPath> selectList = logPathMapper.selectList(queryWrapper);
		for (LogPath logPath : selectList) {
			items.add(logPath.getIdLogPath());
		}
		pathHisCombo.setItems(items);
		QueryWrapper<ConnectionInfo> query = new QueryWrapper<ConnectionInfo>();
		List<ConnectionInfo> selectList2 = connectionInfoMapper.selectList(query);
		if (!selectList2.isEmpty()) {
			List<String> lists = new ArrayList<String>();
			for (ConnectionInfo con : selectList2) {
				lists.add(con.getIdHost()+":"+con.getCdPort());
			}
//			List<String> collect = selectList2.stream().map(e -> e.getIdHost()).collect(Collectors.toList());
			connectHisCombo.setItems(lists);
		}
	}
	

	@Override
	public void registerHandler() {

		searchBtn.addClickListener(e -> {
			if (null == channel) {
				Notification.show("请先连接服务器，再搜索文件", Notification.Type.WARNING_MESSAGE);
				return;
			}
			readLogFile();
		});
		reConnectBtn.addClickListener(e -> {
			if (null != win) {
				win.refresh();
				UI.getCurrent().addWindow(win);
				win.setModal(true);
			} else {
				win = new MySshWindow("连接信息输入");
				UI.getCurrent().addWindow(win);
				win.setModal(true);
			}
		});
		
		addConnectBtn.addClickListener( e ->{
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
//		win = new MySshWindow("连接信息输入");
//		UI.getCurrent().addWindow(win);
		connectHisCombo.addValueChangeListener(e ->{
			String hostId = connectHisCombo.getValue();
			if (null == hostId) {
				return;
			}
			QueryWrapper<ConnectionInfo> query = new QueryWrapper<ConnectionInfo>();
			query.eq("id_host", hostId.split(":")[0]);
			List<ConnectionInfo> selectList2 = connectionInfoMapper.selectList(query);
			if (hostId != null && hostId.contains(":")) {
				hostName = hostId.split(":")[0];
				sshPort = Integer.valueOf(hostId.split(":")[1]);
				userName = selectList2.get(0).getIdUser();
				password = selectList2.get(0).getCdPassword();
			}
//			加载对应的日志历史路径
			QueryWrapper<LogPath> queryWrapper = new QueryWrapper<LogPath>();
			queryWrapper.eq("id_loghost", hostName);
			List<LogPath> selectList = logPathMapper.selectList(queryWrapper);
			for (LogPath logPath : selectList) {
				items.add(logPath.getIdLogPath());
			}
			pathHisCombo.setItems(items);
		});
	}
	

	private void readLogFile() {
		if (pathField.getValue() != null && !pathField.getValue().equals("")) {
			try {
				LogPath path = new LogPath();
				path.setIdLoghost(hostName);
				path.setIdLogPath(pathField.getValue());
				logPathMapper.insert(path);
			} catch (Exception e1) {
				log.error("路径已经存在");
			}
			boolean existDir = isExistDir(pathField.getValue(), channel);
			if (existDir) {
				try {
					Vector<LsEntry> ls = channel.ls(pathField.getValue());
					if (null == ls) {
						Notification.show("该目录没有读取权限，请联系管理员", Notification.Type.WARNING_MESSAGE);
						return;
					} else {
						loadFiles(pathField.getValue(), ls);
						loadSearchResults();
					}
				} catch (SftpException e) {
					Notification.show("该目录没有读取权限，请联系管理员", Notification.Type.WARNING_MESSAGE);
					e.printStackTrace();
					return;
				}
			}else {
				Notification.show("该目录不存在", Notification.Type.WARNING_MESSAGE);
				return;
			}
			// InputStream inputStream = channel.get(pathField.getValue());
		} else if (pathHisCombo.getValue() != null) {
			// 从历史记录中获取
			boolean existDir = isExistDir(pathHisCombo.getValue(), channel);
			if (existDir) {
				try {
					Vector<LsEntry> ls = channel.ls(pathHisCombo.getValue());
					if (null == ls) {
						Notification.show("该目录没有读取权限，请联系管理员", Notification.Type.WARNING_MESSAGE);
						return;
					} else {
						loadFiles(pathHisCombo.getValue(), ls);
						loadSearchResults();
					}
				} catch (SftpException e) {
					Notification.show("该目录没有读取权限，请联系管理员", Notification.Type.WARNING_MESSAGE);
					e.printStackTrace();
					return;
				}
			}else {
				Notification.show("该目录不存在", Notification.Type.WARNING_MESSAGE);
				return;
			}
		} else {
			Notification.show("请输入路径后再搜索", Notification.Type.WARNING_MESSAGE);
			return;
		}
	}


	private void loadSearchResults() {
		resultLayout.removeAllComponents();
		resultLayout.setHeight((searchFileList.size()*52+80)+"px");
		int i = 0;
		if (searchFileList.isEmpty()){
			Notification.show("当前路径下未搜索到匹配后缀的文件", Notification.Type.WARNING_MESSAGE);
			return;
		}
		//添加个表头
		Collections.sort(searchFileList);
		for (PathEntityInfo info : searchFileList) {
			HorizontalLayout layout = ComponentFactory.getHorizontalLayout();
			layout.addStyleName("remote-server-list-even");
			layout.setWidth("60%");
			Label fileNamelb = new Label(info.getFileName());
			fileNamelb.addStyleName("remote-log-search-lb");
			Label date2Lb = new Label(info.getCreateDate());
			date2Lb.addStyleName("remote-log-search-lb");
			
			//file size TODO
			Label fileSizelb = new Label(info.getFileSize()== null ? "未知" : info.getFileSize());
			fileSizelb.addStyleName("remote-log-search-lb");
			Button btn1 = ComponentFactory.getStandardButton("下载");
			FileDownloader fileDownloader = new FileDownloader(new StreamResource(new FileStreamResource(info), info.getFileName()));
			fileDownloader.extend(btn1);
			layout.addComponent(fileNamelb);
			layout.addComponent(date2Lb);
			layout.addComponent(fileSizelb);
			layout.addComponent(btn1);
			resultLayout.addComponent(layout);
			if (i == searchFileList.size()-1) {
				resultLayout.setExpandRatio(layout, 1);
			}
			i++;
		}
	}

	private void loadFiles(String path, Vector<LsEntry> ls) {
		writeSearchPathToFile();
		String suffix = fileSuffixCombo.getValue();
		searchFileList = new ArrayList<PathEntityInfo>();
		for (LsEntry en : ls) {
			if (containsCombo.getValue().equals("是")) {
				if (en.getFilename().endsWith(suffix) || en.getFilename().contains(suffix)) {
					loadFiles(path, suffix, en);
				}
			}else {
				if (suffix.equals("*")){
					loadFiles(path, suffix, en);
				}else if (en.getFilename().endsWith(suffix)) {
					loadFiles(path, suffix, en);
				}
			}
		}
	}

	private void loadFiles(String path, String suffix, LsEntry en) {
		List<String> fileAttributes = getFileAttributes(en);
		if (null != fileAttributes && !fileAttributes.isEmpty() && null != fileAttributes.get(0)
				&& !fileAttributes.get(0).startsWith("d")) {
			Date date = getFileLastModified(fileAttributes);
			PathEntityInfo info = new PathEntityInfo();
			info.setFileName(en.getFilename());
			String fileSize = getFileSize(fileAttributes);
			info.setFileSize(fileSize);
			info.setAbsolutePath(path+"/"+en.getFilename());
			info.setSuffix(suffix);
			info.setCreateDate(Util.formatDate(date));
			searchFileList.add(info);
		}
	}

	private String getFileSize(List<String> fileAttributes) {
		if (fileAttributes.size() > 4) {
			String string = fileAttributes.get(4);
			if (string.endsWith("G") || string.endsWith("M") || string.endsWith("K")) {
				return string;
			}else {
				try {
					double parseDouble = Double.parseDouble(string);
					//1KB
					if (parseDouble < 1024) {
						return string + "Byte";
					}else if (1024 < parseDouble && parseDouble <102400) {
							return NumberUtil.decimalFormat("0.00",parseDouble/1024) + "KB";
						}else if (102400 < parseDouble && parseDouble < 943718400) {
							return NumberUtil.decimalFormat("0.00",(parseDouble/1024)/1024) +"MB";
						}else {
							return NumberUtil.decimalFormat("0.00",((parseDouble/1024)/1024)/1024) +"GB";
						}
				} catch (NumberFormatException e) {
					log.error("解析文件大小出现错误{}",e.getMessage());
					e.printStackTrace();
					return null;
				}
			}
		}
		return null;
	}

	private Date getFileLastModified(List<String> fileAttributes) {
		if (fileAttributes.size() >= 8) {
			Calendar instance = Calendar.getInstance();
			int year = instance.get(Calendar.YEAR);
			if (!fileAttributes.get(7).contains(":")) {
				year = Integer.parseInt(fileAttributes.get(7));
			}
			// Calendar 的月份是 0 基的，Util.getMonth 返回 1~12，必须减 1，
			// 否则所有文件的"修改日期"都会往后偏一个月
			instance.set(year, Util.getMonth(fileAttributes.get(5)) - 1, Integer.parseInt(fileAttributes.get(6)));
			Date time = instance.getTime();
			return time;
		}
		return null;
	}
//	[-rw-r-----, 1, sl, sl, 524329, Jul, 15, 11:29, cas.log.1]
	private List<String> getFileAttributes(LsEntry en) {
		List<String> list = new ArrayList<String>();
		String[] split = en.getLongname().split(" ");
		for (int i = 0; i < split.length; i++) {
			if (!split[i].trim().equals("")) {
				list.add(split[i]);
			}
		}
		return list;
	}

	private void writeSearchPathToFile() {
		if (pathField.getValue() == null || pathField.getValue().equals("")) {
			return;
		}
		if (items.contains(pathField.getValue())) {
			return;
		}
		items.add(pathField.getValue());
		pathHisCombo.setItems(items);
	}


	/**
	 * 
	 * @author Administrator
	 *
	 */
	class MySshWindow extends Window {
		private static final long serialVersionUID = 3394269109511557536L;
		private TextField usernameField;
		private TextField passField;
		private TextField port;
//TODO
		public void refresh() {
			if (null != hostName) {
				host.setValue(hostName);
			}
			if (null != sshPort) {
				port.setValue(String.valueOf(sshPort));
			}
			if (null != userName) {
				usernameField.setValue(userName);
			}
			if (null != password) {
				passField.setValue(password);
			}
		}
		public void clear() {
			host.setValue("");
			port.setValue(String.valueOf(22));
			usernameField.setValue("");
			passField.setValue("");
		}
		public MySshWindow(String title) {
			super(title); // Set window caption
			center();
			setClosable(true);
			setHeight("400px");
			setWidth("370px");

			VerticalLayout ver = new VerticalLayout();
			ver.setSizeFull();
			FormLayout lay = new FormLayout();
			lay.setHeight("230px");
			host = ComponentFactory.getStandardTtextField("主机：");
			port = new TextField("端口：");
			port.setValue("22");
			usernameField = ComponentFactory.getStandardTtextField("用户名：");
			
			passField = ComponentFactory.getStandardPassedwordField("密码：");
			
			lay.addComponent(host);
			lay.addComponent(port);
			lay.addComponent(usernameField);
			lay.addComponent(passField);
			ver.addComponent(lay);
			AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
			abs.setHeight("100px");
			ver.addComponent(abs);
			ver.setExpandRatio(abs, 1);
			Button confirm = ComponentFactory.getStandardButton("连接", e -> {
				if (null != usernameField.getValue() && !"".equals(usernameField.getValue()) && null != passField.getValue()
						&& !"".equals(passField.getValue()) && null != host.getValue() && !"".equals(host.getValue())) {
					hostName = host.getValue();
					sshPort = Integer.valueOf(port.getValue());
					userName = usernameField.getValue();
					password = passField.getValue();
					if (null != channel) {
						Notification.show("请打开新的tab连接另一个服务器", Notification.Type.WARNING_MESSAGE);
						return;
					}
					try {
						if (null != loader.getFile()) {
							//上传了秘钥
							log.info("使用秘钥连接");
							session = JschUtil.createSession(hostName, sshPort, userName, loader.getKeypath(), password == null ? null :password.getBytes());
							channel = JschUtil.openSftp(session, 1800);
						}else {
							session = JschUtil.createSession(hostName, sshPort, userName, password);
							channel = JschUtil.openSftp(session, 1800);
						}
					} catch (Exception ex) {
						log.error(ExceptionUtils.getStackTrace(ex));
						Notification.show("创建链接失败，请检查IP、端口、用户名是否有误！", Notification.Type.WARNING_MESSAGE);
						return;
					}

					// 如果连接成功保存用户的配置
//					1saveUserConfig(host.getValue(), Integer.valueOf(port.getValue()), usernameField.getValue(), passField.getValue());
					try {
						connectionInfoMapper.insert(new ConnectionInfo(host.getValue(), port.getValue(), usernameField.getValue(), passField.getValue(), loader.getKeypath()));
					} catch (Exception e1) {
						// 原来只打一句"链接信息已存在"，真实原因（主键冲突 / 数据库不可用）全被吞掉
						log.error("保存连接信息失败：{}:{}", host.getValue(), port.getValue(), e1);
						Notification.show("该连接已存在或保存失败，请检查", Notification.Type.WARNING_MESSAGE);
						return;
					}
					this.close();
					Notification.show("链接成功，可以开始搜索和下载文件了。", Notification.Type.WARNING_MESSAGE);
				} else {
					Notification.show("用户名或密码输入有误", Notification.Type.WARNING_MESSAGE);
					return;
				}
				getConfigForHis();
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
			refresh();
		}
	}

	public void saveUserConfig(String host, Integer port, String userName, String password) {
		boolean checkUserConfig = Util.checkUserConfig(host);
		if (checkUserConfig) {
			return;
		}
		StringBuffer buffer = new StringBuffer();
		buffer.append(host);
		buffer.append("=");
		buffer.append(port + "");
		buffer.append("=");
		buffer.append(userName);
		buffer.append("=");
		buffer.append(password);
		buffer.append("=ssh");
		Util.saveUserConfigToFile(buffer.toString());
	}

	/**
	 * 判断路径是否存在
	 * 
	 * @param path
	 * @param sftp
	 * @return
	 */
	public boolean isExistDir(String path, ChannelSftp sftp) {
		boolean isExist = false;
		try {
			SftpATTRS sftpATTRS = sftp.lstat(path);
			isExist = true;
			return sftpATTRS.isDir();
		} catch (Exception e) {
			// e.getMessage() 可能为 null，原写法直接 .toLowerCase() 会再抛一个 NPE
			String message = e.getMessage();
			if (null != message && message.toLowerCase().contains("no such file")) {
				isExist = false;
			} else {
				log.warn("判断远程路径 {} 是否存在时出错：{}", path, message);
			}
		}
		return isExist;

	}
	
	class FileStreamResource implements StreamSource {

		private static final long serialVersionUID = 6327185867459484865L;
		private PathEntityInfo filePathInfo;
		
		public FileStreamResource(PathEntityInfo filePathInfo) {
			super();
			this.filePathInfo = filePathInfo;
		}

		@Override
		public InputStream getStream() {
			InputStream inputStream;
			try {
				inputStream = channel.get(filePathInfo.getAbsolutePath());
				return inputStream;
			} catch (SftpException e) {
				e.printStackTrace();
			}
			return null;
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

}
