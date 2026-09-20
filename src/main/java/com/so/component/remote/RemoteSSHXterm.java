package com.so.component.remote;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.so.component.CommonComponent;
import com.so.entity.ConnectionInfo;
import com.so.ui.ComponentFactory;
import com.vaadin.server.ExternalResource;
import com.vaadin.ui.BrowserFrame;
import com.vaadin.ui.Panel;
import com.vaadin.ui.VerticalLayout;

/**
 * 基于 xterm 的 Web 终端页面。
 * <p>
 * 页面本身只是把 {@code VAADIN/themes/mytheme/terminal.html} 嵌进来，
 * 真正的 ssh 通道由 {@code com.so.controller.SshHandler} 通过 websocket 持有。
 * <p>
 * 连接信息不再放在静态字段里共享（那样多用户同时使用时，A 用户打开的终端会连到
 * B 用户选中的机器），而是每次打开 tab 生成一个随机 token 登记到
 * {@link #PENDING_CONNECTIONS}，terminal.html 建立 websocket 时带上 token。
 * <p>
 * token 是绑定在这个浏览器页面上的随机串，<b>允许重复使用</b>（页面上的"重新连接"
 * 要能用），但带有效期，且页面 detach 时会立即回收。
 */
@Service
@Scope("prototype")
public class RemoteSSHXterm extends CommonComponent {

	private static final long serialVersionUID = 1L;

	private static final Logger log = LoggerFactory.getLogger(RemoteSSHXterm.class);

	private static final String TERMINAL_PAGE = "VAADIN/themes/mytheme/terminal.html";

	/** 登记表容量上限，超过就先清掉过期的，仍然超就整体清空 */
	private static final int MAX_PENDING = 500;

	/** token 有效期，6 小时 */
	private static final long TOKEN_TTL_MS = 6 * 60 * 60 * 1000L;

	/**
	 * 待用连接信息登记表：token -> 连接信息。
	 */
	public static final Map<String, PendingEntry> PENDING_CONNECTIONS = new ConcurrentHashMap<String, PendingEntry>();

	/** 一次性终端凭证：token 本身是页面级随机的，不跨用户共享 */
	public static final class PendingEntry {
		private final ConnectionInfo info;
		private volatile long expireAt;

		PendingEntry(ConnectionInfo info, long expireAt) {
			this.info = info;
			this.expireAt = expireAt;
		}
	}

	private Panel mainPanel;
	private VerticalLayout contentLayout;
	private ConnectionInfo addr;
	private String hostName;
	/** 当前页面使用的 token，销毁时回收 */
	private String token;

	@Override
	public void initLayout() {
		mainPanel = new Panel();
		contentLayout = new VerticalLayout();
		setCompositionRoot(mainPanel);
		mainPanel.setContent(contentLayout);
		contentLayout.setWidth("100%");
		contentLayout.setHeight("700px");
		initMainLayout();
	}

	private void initMainLayout() {
		BrowserFrame frame = new BrowserFrame(null, new ExternalResource(buildTerminalUrl()));
		frame.setSizeFull();
		contentLayout.addComponent(frame);
		contentLayout.addComponent(ComponentFactory.getStandardLabel(
				"终端为完整的 VT 实现，vim / top / tmux 等全屏程序可直接使用。"
						+ "快捷键：Ctrl+F 搜索缓冲区，Ctrl+Shift+C 复制选中内容，Ctrl+Shift+V 粘贴；"
						+ "工具栏可导出整个会话文本。若卡在全屏程序里无法退出，按 Esc 后输入 :q! 或 q 即可。"));
		contentLayout.setExpandRatio(frame, 1.0f);
	}

	/**
	 * 生成 token 并拼出终端页面地址。
	 */
	private String buildTerminalUrl() {
		releaseToken();
		purgeExpired();
		if (PENDING_CONNECTIONS.size() >= MAX_PENDING) {
			PENDING_CONNECTIONS.clear();
			log.warn("终端连接登记表超过上限，已整体清空");
		}
		token = UUID.randomUUID().toString().replace("-", "");
		PENDING_CONNECTIONS.put(token, new PendingEntry(addr, System.currentTimeMillis() + TOKEN_TTL_MS));
		return TERMINAL_PAGE + "?token=" + token;
	}

	/**
	 * websocket 建立时按 token 取连接信息，取到就续期。
	 * <p>
	 * 这里不再"取出即删"：页面上点"重新连接"、或者 iframe 被浏览器重载时，
	 * 用的还是同一个 token，删掉就再也连不上了。
	 */
	public static ConnectionInfo resolvePending(String token) {
		if (null == token || token.isEmpty()) {
			return null;
		}
		purgeExpired();
		PendingEntry entry = PENDING_CONNECTIONS.get(token);
		if (null == entry) {
			return null;
		}
		entry.expireAt = System.currentTimeMillis() + TOKEN_TTL_MS;
		return entry.info;
	}

	private static void purgeExpired() {
		long now = System.currentTimeMillis();
		PENDING_CONNECTIONS.entrySet().removeIf(e -> e.getValue().expireAt < now);
	}

	private void releaseToken() {
		if (null != token) {
			PENDING_CONNECTIONS.remove(token);
			token = null;
		}
	}

	@Override
	public void detach() {
		releaseToken();
		super.detach();
	}

	@Override
	public void initContent() {
	}

	@Override
	public void registerHandler() {
	}

	public ConnectionInfo getAddr() {
		return addr;
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
