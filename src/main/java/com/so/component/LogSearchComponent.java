package com.so.component;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.so.ui.HorizontalGroupLayout;
import com.so.ui.ComponentFactory;
import com.so.util.Constants;
import com.so.util.Util;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.component.util.TabSheetUtil;
import com.so.entity.LogPath;
import com.so.entity.PathEntityInfo;
import com.so.mapper.LogPathMapper;
import com.vaadin.ui.Button;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.Grid;
import com.vaadin.ui.Grid.SelectionMode;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TextField;
import com.vaadin.ui.VerticalLayout;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.system.SystemUtil;

/**
 * 首页展示的内容
 * 
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class LogSearchComponent extends CommonComponent {

	private static final Logger log = LoggerFactory.getLogger(LogSearchComponent.class);

	private static final long serialVersionUID = 1578091801743458376L;

	private Panel mainPanel;

	private ComboBox<String> pathCombo;
	private ComboBox<String> fileSuffixCombo;
	private ComboBox<String> fileEncodingCombo;
	private Button btn;
	private VerticalLayout contentLayout;


	private TextField pathField;

	private HashSet<String> items = new HashSet<String>();

	private ArrayList<PathEntityInfo> searchFileList;

	private Grid<PathEntityInfo> grid;

	private ComboBox<Object> ContainCombo;
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
		HorizontalGroupLayout abs = new HorizontalGroupLayout(new Integer[]{ 1, 3, 1, 2, 1, 1, 2,2});
		abs.setHeight("41px");
		contentLayout.addComponent(abs);
		Label pathLb = new Label("输入日志路径：");
		abs.getAbsoluteLayouts().get(0).addComponent(pathLb);

		pathField = ComponentFactory.getStandardTtextField();
		pathField.setWidth("370px");
		pathField.setDescription("请输入文件所在路径和后缀后点击搜索");
		abs.getAbsoluteLayouts().get(1).addComponent(pathField);

		// 文件后缀默认log
		Label suffixLogLb = new Label("选择后缀：");
		abs.getAbsoluteLayouts().get(2).addComponent(suffixLogLb);
		fileSuffixCombo = ComponentFactory.getStandardComboBox();
		fileSuffixCombo.setEmptySelectionAllowed(false);
		fileSuffixCombo.setItems(Util.getFileSuffix());
		fileSuffixCombo.setValue("log");
		abs.getAbsoluteLayouts().get(3).addComponent(fileSuffixCombo);

		btn = ComponentFactory.getStandardButton("搜索日志", e -> {
			readLogFile();
		});
		abs.getAbsoluteLayouts().get(4).addComponent(btn);

		// 编码默认utf-8
		Label codeLogLb = new Label("选择编码：");
		abs.getAbsoluteLayouts().get(5).addComponent(codeLogLb);
		fileEncodingCombo = ComponentFactory.getStandardComboBox();
		fileEncodingCombo.setEmptySelectionAllowed(false);
		fileEncodingCombo.setItems(Constants.ISO_8859_1, Constants.GBK, Constants.UTF_8);
		// 必须给默认值：原实现这行被注释掉，配合 setEmptySelectionAllowed(false)
		// 会让下拉框为空，LogDetailComponent 拿到 null 编码后回退到平台默认编码，
		// 在 Linux 上打开 GBK 日志就会乱码
		fileEncodingCombo.setValue(Constants.UTF_8);
		abs.getAbsoluteLayouts().get(6).addComponent(fileEncodingCombo);

	}

	/**
	 * 展示历史输入路径
	 */
	private void initHisInputLayout() {
		HorizontalGroupLayout abshis = new HorizontalGroupLayout(new Integer[]{ 1, 3, 1, 2, 1,1, 2, 2});
		abshis.setHeight("41px");
		contentLayout.addComponent(abshis);
		Label pathLb = new Label("搜索历史：");
		abshis.getAbsoluteLayouts().get(0).addComponent(pathLb);

		pathCombo = ComponentFactory.getStandardComboBox();
		pathCombo.setWidth("370px");
		pathCombo.setTextInputAllowed(true);
		abshis.getAbsoluteLayouts().get(1).addComponent(pathCombo);
		
		Label containLb = new Label("包含模式：");
		containLb.setDescription("文件名中包含后缀的文件");
		abshis.getAbsoluteLayouts().get(2).addComponent(containLb);
		
		ContainCombo = ComponentFactory.getStandardComboBox();
		ContainCombo.setEmptySelectionAllowed(false);
		ContainCombo.setItems("是","否");
		ContainCombo.setValue("否");
		abshis.getAbsoluteLayouts().get(3).addComponent(ContainCombo);
		
		// 其他功能
		Label des = new Label("搜索结果列表(点击可打开)");
		contentLayout.addComponent(des);
		Label line = new Label();
		line.setHeight("2px");
		line.addStyleName("split_line");
		contentLayout.addComponent(line);
	}

	private void initLogSearchResultLayout() {
		// 显示搜索的日志文件列表
		HorizontalGroupLayout logSearchResults = new HorizontalGroupLayout(new Integer[] { 2, 2 });
		contentLayout.addComponent(logSearchResults);
		grid = new Grid<PathEntityInfo>();
		grid.setWidth("100%");
		grid.setSelectionMode(SelectionMode.SINGLE);
		grid.addColumn(PathEntityInfo:: getFileName).setCaption("文件名");
		grid.addColumn(PathEntityInfo:: getFileSize).setCaption("文件大小");
		grid.addColumn(PathEntityInfo:: getCreateDate).setCaption("修改日期");
		grid.addComponentColumn(p ->{
			Button btn = ComponentFactory.getStandardButton("预览");
			btn.addClickListener( e ->openLogFile(p));
			return btn;
		}).setCaption("预览");
		grid.addComponentColumn(p ->{
			Button btn = ComponentFactory.getStandardButton("下载");
			FileDownloader fileDownloader = new FileDownloader(new StreamResource(new FileStreamResource(p), p.getFileName()));
			fileDownloader.extend(btn);
			return btn;
		}).setCaption("下载");
//		grid.addItemClickListener(e ->{
//			openLogFile(e.getItem());
//		});
		logSearchResults.getAbsoluteLayouts().get(0).addComponent(grid);
		contentLayout.setExpandRatio(logSearchResults, 1);

	}

	private void openLogFile(PathEntityInfo set) {
		String fileName = set.getFileName() == null ? "" : set.getFileName().toLowerCase();
		if (fileName.endsWith("pdf") || fileName.endsWith("doc") || fileName.endsWith("docx") ||
				fileName.endsWith("xls") || fileName.endsWith("xlsx")){
			Notification.show("支持文本文档在线打开，请下载后查看");
			return;
		}
			LogDetailComponent logDetailComponent = new LogDetailComponent();
			logDetailComponent.setEncoding(fileEncodingCombo.getValue());
			logDetailComponent.setFilePathInfo(set);
			logDetailComponent.initLayout();
			logDetailComponent.initContent();
			logDetailComponent.registerHandler();
			TabSheetUtil.getMainTabsheet().addTab(logDetailComponent, SystemUtil.getHostInfo().getAddress() + set.getFileName())
			.setClosable(true);
			TabSheetUtil.getMainTabsheet().setSelectedTab(logDetailComponent);
	}

	private void readLogFile() {
		if (pathField.getValue() != null && !pathField.getValue().equals("")) {
			try {
				LogPath path = new LogPath();
				path.setIdLoghost("localhost");
				path.setIdLogPath(pathField.getValue());
				logPathMapper.insert(path);
			} catch (Exception e) {
				log.error("路径已经存在");
			}
			File file = new File(pathField.getValue());
			loadFilesForSearch(file);
		} else if (pathCombo.getValue() != null) {
			// 从历史记录中获取
			File file = new File(pathCombo.getValue());
			loadFilesForSearch(file);
		} else {
			Notification.show("请输入路径后再搜索", Notification.Type.WARNING_MESSAGE);
			return;
		}
	}

	private void loadFilesForSearch(File file) {
		if (!file.exists()) {
			Notification.show("该目录不存在", Notification.Type.WARNING_MESSAGE);
			return;
		}
		if (!file.isDirectory()) {
			Notification.show("请输入目录路径，不能是文件", Notification.Type.WARNING_MESSAGE);
			return;
		}
		if (!file.canRead()) {
			Notification.show("该目录没有读取权限，请联系管理员", Notification.Type.WARNING_MESSAGE);
			return;
		}
		try {
			loadFiles(file);
			initGridContent();
		} catch (IOException e) {
			log.error("读取用户配置文件错误：{}", ComponentUtil.getCurrentUserName(), e);
		}
	}

	private void loadFiles(File file) throws IOException {
		// 将用户搜索的路径添加到历史搜索文件中
		writeSearchPathToFile();
		String suffix = fileSuffixCombo.getValue();
		searchFileList = new ArrayList<PathEntityInfo>();
		File[] listFiles = file.listFiles();
		// listFiles() 在目录不可读或不是目录时会返回 null，原实现直接遍历 -> NPE
		if (listFiles == null) {
			Notification.show("该目录无法读取", Notification.Type.WARNING_MESSAGE);
			return;
		}
		boolean containsMode = "是".equals(ContainCombo.getValue());
		boolean anySuffix = "*".equals(suffix);
		for (File item : listFiles) {
			if (!item.isFile()) {
				continue;
			}
			String name = item.getName();
			boolean matched = containsMode ? name.contains(suffix) : (anySuffix || name.endsWith(suffix));
			if (!matched) {
				continue;
			}
			PathEntityInfo info = new PathEntityInfo();
			info.setFileName(name);
			// 原来用 length/1024 整数除法，小于 1KB 的文件一律显示 0kb
			info.setFileSize(formatFileSize(item.length()));
			info.setAbsolutePath(item.getAbsolutePath());
			info.setSuffix(suffix);
			info.setCreateDate(Util.formatDate(new Date(item.lastModified())));
			searchFileList.add(info);
		}
	}

	/**
	 * 把字节数格式化成可读字符串
	 */
	private static String formatFileSize(long bytes) {
		if (bytes < 1024) {
			return bytes + "B";
		}
		if (bytes < 1024L * 1024) {
			return NumberUtil.decimalFormat("0.00", bytes / 1024.0) + "KB";
		}
		if (bytes < 1024L * 1024 * 1024) {
			return NumberUtil.decimalFormat("0.00", bytes / 1024.0 / 1024) + "MB";
		}
		return NumberUtil.decimalFormat("0.00", bytes / 1024.0 / 1024 / 1024) + "GB";
	}

	private void writeSearchPathToFile() throws IOException {
		if (pathField.getValue() == null || pathField.getValue().equals("")) {
			return;
		}
		if (items.contains(pathField.getValue())) {
			return;
		}
		items.add(pathField.getValue());
		pathCombo.setItems(items);
	}

	@Override
	public void initContent() {
		getConfigForHis();
	}

	private void initGridContent() {
		Collections.sort(searchFileList);
		grid.setItems(searchFileList);
		grid.markAsDirty();
	}
	
	private void getConfigForHis() {
		QueryWrapper<LogPath> queryWrapper = new QueryWrapper<LogPath>();
		queryWrapper.eq("id_loghost", "localhost");
		List<LogPath> selectList = logPathMapper.selectList(queryWrapper);
		for (LogPath logPath : selectList) {
			items.add(logPath.getIdLogPath());
		}
		pathCombo.setItems(items);
	}

	@Override
	public void registerHandler() {
		// TODO Auto-generated method stub

	}
	class FileStreamResource implements StreamResource.StreamSource {

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
				return new FileInputStream(new File(filePathInfo.getAbsolutePath()));
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
