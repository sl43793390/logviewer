package com.so.component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.so.ui.ComponentFactory;
import com.so.entity.PathEntityInfo;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import com.vaadin.server.StreamResource.StreamSource;
import com.vaadin.ui.AbsoluteLayout;
import com.vaadin.ui.Button;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.TextArea;
import com.vaadin.ui.VerticalLayout;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.json.JSONUtil;

/**
 * 日志详细展示页面
 * <p>
 * 分页策略：不再把整个文件读进内存（原实现 FileUtil.readLines 全量加载，
 * 一个几百 MB 的日志就能把堆打爆），而是先建立行偏移索引，再按需 seek 读取当前页。
 * 
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class LogDetailComponent extends CommonComponent {

	private static final Logger log = LoggerFactory.getLogger(LogDetailComponent.class);

	private static final long serialVersionUID = 6472452553393605385L;

	/** 每页行数 */
	private static final int PAGE_SIZE = 500;

	/** 单页最多读取的字节数，防止"一行几个 G"的异常文件把内存吃满 */
	private static final long MAX_PAGE_BYTES = 8L * 1024 * 1024;

	/** 建索引时的读取块大小 */
	private static final int INDEX_BUFFER_SIZE = 64 * 1024;

	/** JSON 美化模式允许的最大文件体积 */
	private static final long MAX_JSON_BYTES = 5L * 1024 * 1024;

	/** 单行最多显示的字符数，避免超长单行把浏览器拖死 */
	private static final int MAX_LINE_LENGTH = 20000;

	private Panel mainPanel;
	private TextArea textArea;
	private String fileEncoding;
	private PathEntityInfo filePathInfo;
	private VerticalLayout contentLayout;
	private Label pageLb;
	private Button preLogBtn;
	private Button nextLogBtn;
	private Button dowloadLogBtn;

	private RandomAccessFile accessFile;
	private long fileLength;
	/** 行偏移索引，offsets[i] 为第 i 行的起始字节位置，末位固定为文件长度 */
	private long[] lineOffsets;
	/** 当前页，从 1 开始 */
	private int currentPage = 1;
	/** 总页数 */
	private int totalPages = 1;

	@Override
	public void initLayout() {
		mainPanel = new Panel();
		contentLayout = new VerticalLayout();
		setCompositionRoot(mainPanel);
		mainPanel.setContent(contentLayout);
		contentLayout.setWidth("100%");
		contentLayout.setHeight("725px");
		initMainLayout();
		// 下载按钮的 FileDownloader 只注册一次。
		// 原实现在 initContent 和每次点击时都 new 一个 FileDownloader 并 extend，
		// 同一个按钮上会不断叠加下载器
		String downloadName = (null != filePathInfo && null != filePathInfo.getFileName())
				? filePathInfo.getFileName() : "log.txt";
		FileDownloader downloader = new FileDownloader(new StreamResource(new FileStreamResource(), downloadName));
		downloader.extend(dowloadLogBtn);
	}

	/**
	 * 布局
	 */
	private void initMainLayout() {
		AbsoluteLayout abs = new AbsoluteLayout();
		abs.setWidth("100%");
		abs.setHeightFull();
		contentLayout.addComponent(abs);

		textArea = new TextArea("日志详细信息");
		textArea.setWidth("100%");
		textArea.setHeight("640px");

		dowloadLogBtn = ComponentFactory.getStandardButton("下载");
		preLogBtn = ComponentFactory.getStandardButton("上一页", e -> loadPreviousPage());
		nextLogBtn = ComponentFactory.getStandardButton("下一页", e -> loadNextPage());
		pageLb = new Label("第1页");
		abs.addComponent(textArea);
		abs.addComponent(pageLb, "bottom:10px;left:50px;");
		abs.addComponent(dowloadLogBtn, "bottom:10px;right:230px;");
		abs.addComponent(preLogBtn, "bottom:10px;right:120px;");
		abs.addComponent(nextLogBtn, "bottom:10px;right:10px;");
	}

	@Override
	public void initContent() {
		if (null == fileEncoding) {
			fileEncoding = CharsetUtil.defaultCharset().name();
		}
		if (null == filePathInfo || null == filePathInfo.getAbsolutePath()) {
			return;
		}
		File file = new File(filePathInfo.getAbsolutePath());
		if (!file.isFile()) {
			Notification.show("文件不存在或不可读", Notification.Type.WARNING_MESSAGE);
			return;
		}
		this.fileLength = file.length();

		String suffix = filePathInfo.getSuffix() == null ? "" : filePathInfo.getSuffix().toLowerCase();
		if ("json".equals(suffix) && fileLength <= MAX_JSON_BYTES) {
			if (loadPrettyJson(file)) {
				return;
			}
			// 不是合法 json，退回到分页展示
		}

		if (!buildIndex(file)) {
			return;
		}
		// 与原来的行为保持一致：默认停在最后一页
		currentPage = totalPages;
		renderCurrentPage();
	}

	/**
	 * 建立行偏移索引
	 *
	 * @return 是否成功
	 */
	private boolean buildIndex(File file) {
		closeAccessFile();
		try {
			accessFile = new RandomAccessFile(file, "r");
			long[] offsets = new long[1024];
			int count = 0;
			offsets[count++] = 0;
			byte[] buffer = new byte[INDEX_BUFFER_SIZE];
			long position = 0;
			int read;
			while ((read = accessFile.read(buffer)) != -1) {
				for (int i = 0; i < read; i++) {
					if (buffer[i] == '\n') {
						long nextLine = position + i + 1;
						// 文件以换行结尾时不再产生一个空行
						if (nextLine < fileLength) {
							if (count == offsets.length) {
								offsets = Arrays.copyOf(offsets, count * 2);
							}
							offsets[count++] = nextLine;
						}
					}
				}
				position += read;
			}
			// 末尾放文件长度，作为最后一行的结束位置
			if (count == offsets.length) {
				offsets = Arrays.copyOf(offsets, count + 1);
			}
			offsets[count++] = fileLength;
			lineOffsets = Arrays.copyOf(offsets, count);

			long totalLines = lineOffsets.length - 1L;
			totalPages = (int) Math.max(1, Math.ceil((double) totalLines / PAGE_SIZE));
			accessFile.seek(0);
			return true;
		} catch (IOException e) {
			log.error("建立日志行索引失败：{}", filePathInfo.getAbsolutePath(), e);
			Notification.show("读取日志失败：" + e.getMessage(), Notification.Type.ERROR_MESSAGE);
			closeAccessFile();
			return false;
		}
	}

	private void loadNextPage() {
		if (currentPage >= totalPages) {
			Notification.show("当前已经是最后一页", Notification.Type.WARNING_MESSAGE);
			return;
		}
		currentPage++;
		renderCurrentPage();
	}

	private void loadPreviousPage() {
		if (currentPage <= 1) {
			Notification.show("当前已经是第一页", Notification.Type.WARNING_MESSAGE);
			return;
		}
		currentPage--;
		renderCurrentPage();
	}

	private void renderCurrentPage() {
		if (null == accessFile || null == lineOffsets) {
			return;
		}
		try {
			int startLine = (currentPage - 1) * PAGE_SIZE;
			int endLine = Math.min(startLine + PAGE_SIZE, lineOffsets.length - 1);
			long startOffset = lineOffsets[startLine];
			long endOffset = lineOffsets[endLine];
			if (endOffset - startOffset > MAX_PAGE_BYTES) {
				endOffset = startOffset + MAX_PAGE_BYTES;
			}

			int length = (int) Math.max(0, endOffset - startOffset);
			byte[] bytes = new byte[length];
			accessFile.seek(startOffset);
			accessFile.readFully(bytes);

			String content = new String(bytes, resolveCharset());
			StringBuilder builder = new StringBuilder(content.length());
			for (String line : content.split("\r\n|\r|\n")) {
				if (line.length() > MAX_LINE_LENGTH) {
					line = line.substring(0, MAX_LINE_LENGTH) + " ...(本行过长，已截断)";
				}
				builder.append(line).append(System.lineSeparator());
			}

			textArea.clear();
			textArea.setValue(builder.toString());
		} catch (IOException e) {
			log.error("读取日志分页失败：{}", filePathInfo.getAbsolutePath(), e);
			Notification.show("读取日志失败：" + e.getMessage(), Notification.Type.ERROR_MESSAGE);
			return;
		}
		pageLb.setValue("第 " + currentPage + " / " + totalPages + " 页（每页 " + PAGE_SIZE + " 行）");
	}

	private Charset resolveCharset() {
		if (null == fileEncoding) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(fileEncoding);
		} catch (Exception e) {
			log.warn("不支持的编码 {}，回退到 UTF-8", fileEncoding);
			return StandardCharsets.UTF_8;
		}
	}

	/**
	 * 小文件且是合法 json 时格式化展示
	 *
	 * @return 是否走 json 分支
	 */
	private boolean loadPrettyJson(File file) {
		try {
			List<String> lines = FileUtil.readLines(file, resolveCharset());
			StringBuilder content = new StringBuilder();
			for (String l : lines) {
				content.append(l);
			}
			if (!JSONUtil.isTypeJSON(content.toString())) {
				Notification.show("当前文件不是标准的json文件，无法正确格式化", Notification.Type.WARNING_MESSAGE);
				return false;
			}
			textArea.clear();
			textArea.setValue(JSONUtil.toJsonPrettyStr(JSONUtil.parseObj(content.toString())));
			pageLb.setValue("JSON 格式化展示（共 " + lines.size() + " 行）");
			return true;
		} catch (Exception e) {
			log.warn("json 格式化失败，退回到普通分页展示", e);
			return false;
		}
	}

	@Override
	public void registerHandler() {
	}

	@Override
	public void detach() {
		closeAccessFile();
		super.detach();
	}

	private void closeAccessFile() {
		if (null != accessFile) {
			try {
				accessFile.close();
			} catch (IOException e) {
				log.warn("关闭日志文件失败：{}", e.getMessage());
			} finally {
				accessFile = null;
			}
		}
	}

	public String getEncoding() {
		return fileEncoding;
	}

	public void setEncoding(String encoding) {
		this.fileEncoding = encoding;
	}

	public PathEntityInfo getFilePathInfo() {
		return filePathInfo;
	}

	public void setFilePathInfo(PathEntityInfo filePathInfo) {
		this.filePathInfo = filePathInfo;
	}

	class FileStreamResource implements StreamSource {

		private static final long serialVersionUID = 6327185867459484865L;

		@Override
		public InputStream getStream() {
			if (null == filePathInfo || null == filePathInfo.getAbsolutePath()) {
				return null;
			}
			try {
				return new FileInputStream(new File(filePathInfo.getAbsolutePath()));
			} catch (FileNotFoundException e) {
				log.error("下载文件出现错误：{}", filePathInfo.getAbsolutePath(), e);
			}
			return null;
		}

	}

}
