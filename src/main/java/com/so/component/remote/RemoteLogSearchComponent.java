package com.so.component.remote;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jcraft.jsch.*;
import com.so.component.CommonComponent;
import com.so.entity.LogPath;
import com.so.mapper.LogPathMapper;
import com.so.util.MyJSchUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.so.entity.ConnectionInfo;
import com.so.entity.PathEntityInfo;
import com.so.ui.ComponentFactory;
import com.so.ui.HorizontalGroupLayout;
import com.so.util.Util;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import com.vaadin.server.StreamResource.StreamSource;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TextField;
import com.vaadin.ui.VerticalLayout;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 远程自动登录后跳转的搜索日志页面
 * 
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class RemoteLogSearchComponent extends CommonComponent {

	private static final Logger log = LoggerFactory.getLogger(RemoteLogSearchComponent.class);

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

	private Session jschSession;
	private ChannelSftp channel;

	private ConnectionInfo addr;
	
	private VerticalLayout resultLayout;

	private ComboBox<String> containsCombo;
	@Autowired
	private LogPathMapper logPathMapper;

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
		HorizontalGroupLayout abs = new HorizontalGroupLayout(new Integer[] { 1, 3, 1, 2, 1, 2, 2});
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
		containsLb.setDescription("如果有catalina.log.20200717这种格式可能用后缀模式无法匹配到");
		abs.getAbsoluteLayouts().get(4).addComponent(containsLb);
		containsCombo = ComponentFactory.getStandardComboBox();
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
		queryWrapper.eq("id_loghost", addr.getIdHost());
		List<LogPath> selectList = logPathMapper.selectList(queryWrapper);
		for (LogPath logPath : selectList) {
			items.add(logPath.getIdLogPath());
		}
		pathHisCombo.setItems(items);
	}

	private List<String> getPath(List<String> serverList) {
		List<String> lists = new ArrayList<String>();
		int i = 0;
		for (String string : serverList) {
			if (i == serverList.size()-1) {
				continue;
			}
			if (i>0) {
				lists.add(string);
			}
			i++;
		}
		return lists;
	}

	@Override
	public void registerHandler() {
		searchBtn.addClickListener(e -> {
			readLogFile();
		});
	}

	private void readLogFile() {
		if (null == channel){
			try {
				channel = MyJSchUtil.openSftpChannel(jschSession);
				channel.connect();
			} catch (JSchException e) {
				throw new RuntimeException(e);
			}
		}
		if (pathField.getValue() != null && !pathField.getValue().equals("")) {
			boolean existDir = isExistDir(pathField.getValue(), channel);
			if (existDir) {
				try {
					LogPath path = new LogPath();
					path.setIdLoghost(addr.getIdHost());
					path.setIdLogPath(pathField.getValue());
					logPathMapper.insert(path);
				} catch (Exception e1) {
					log.error("路径已经存在");
				}
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
		Collections.sort(searchFileList);
		for (PathEntityInfo info : searchFileList) {
			HorizontalLayout layout = ComponentFactory.getHorizontalLayout();
			layout.addStyleName("remote-server-list-even");
			layout.setWidth("60%");
			Label fileNameLb = new Label(info.getFileName());
			fileNameLb.addStyleName("remote-log-search-lb");
			Label dateLb = new Label(info.getCreateDate());
			dateLb.addStyleName("remote-log-search-lb");
			
			Label fileSizelb = new Label(info.getFileSize()== null ? "未知" : info.getFileSize());
			fileSizelb.addStyleName("remote-log-search-lb");
			Button btn1 = ComponentFactory.getStandardButton("下载");
			FileDownloader fileDownloader = new FileDownloader(new StreamResource(new FileStreamResource(info), info.getFileName()));
			fileDownloader.extend(btn1);
			layout.addComponent(fileNameLb);
			layout.addComponent(dateLb);
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

	private Date getFileLastModified(List<String> fileAttributes) {
		if (fileAttributes.size() >= 8) {
			Calendar instance = Calendar.getInstance();
			int year = instance.get(Calendar.YEAR);
			if (!fileAttributes.get(7).contains(":")) {
				year = Integer.parseInt(fileAttributes.get(7));
			}
			instance.set(year, Util.getMonth(fileAttributes.get(5))-1, Integer.parseInt(fileAttributes.get(6)));
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
	
	private void writeSearchPathToFile() {
		// TextField 默认值是 ""，但也可能是 null，原来直接 equals("") 会 NPE
		if (StrUtil.isBlank(pathField.getValue())) {
			return;
		}
		if (items.contains(pathField.getValue())) {
			return;
		}
		// 写入
		items.add(pathField.getValue());
		pathHisCombo.clear();
		pathHisCombo.setItems(items);
		String pathStr =addr.getIdHost()+"=" + pathField.getValue() + "=" + "##";
		Util.saveUserConfigToFile(pathStr);
	}

	@Override
	public void detach() {
		super.detach();
		// channel 是本组件自己打开的，页面销毁必须释放，否则每开一次都会漏一个 SFTP channel
		if (null != channel) {
			channel.disconnect();
			channel = null;
		}
	}

	

//	private void readyToConnect() {
//		try {
//			if (addr.getCdKeyPath() == null) {
//				//无秘钥连接
//				jschSession = JschUtil.createSession(addr.getIdHost(), Integer.parseInt(addr.getCdPort()), addr.getIdUser(), addr.getCdPassword());
//				channel = JschUtil.openSftp(jschSession, 1800);
//			}else if(addr.getCdKeyPath() != null){
//				//秘钥连接
//				String currentDir = System.getProperty("user.dir");
//				jschSession = JschUtil.createSession(addr.getIdHost(), Integer.parseInt(addr.getCdPort()), addr.getIdUser(),addr.getCdKeyPath(),  addr.getCdPassword() == null ?null :addr.getCdPassword().getBytes());
//				channel = JschUtil.openSftp(jschSession, 1800);
//			}
//		} catch (NumberFormatException e) {
//			Notification.show("连接失败请检查配置", Notification.Type.WARNING_MESSAGE);
//			e.printStackTrace();
//			return;
//		}
//	}

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
			if (null == channel) {
				log.warn("SFTP channel 已关闭，无法下载 {}", filePathInfo.getAbsolutePath());
				return null;
			}
			try {
				return channel.get(filePathInfo.getAbsolutePath());
			} catch (SftpException e) {
				log.error("下载远程文件失败：{}", filePathInfo.getAbsolutePath(), e);
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
		if (jschSession != null) {
			jschSession.disconnect();
		}
		log.info("disconnected SFTP successfully!");
	}

	public void setAddr(ConnectionInfo addr) {
		this.addr = addr;
	}

	public Session getJschSession() {
		return jschSession;
	}

	public void setJschSession(Session jschSession) {
		this.jschSession = jschSession;
	}
}
