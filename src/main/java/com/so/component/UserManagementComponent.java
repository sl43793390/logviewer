package com.so.component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.vaadin.addons.ComboBoxMultiselect;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.component.util.ConfirmationDialogPopupWindow;
import com.so.entity.User;
import com.so.mapper.UserDao;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.so.util.Util;
import com.vaadin.data.ValueProvider;
import com.vaadin.server.FileDownloader;
import com.vaadin.server.StreamResource;
import com.vaadin.server.VaadinSession;
import com.vaadin.shared.ui.ValueChangeMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.ui.CheckBoxGroup;
import com.vaadin.ui.ComboBox;
import com.vaadin.ui.Component;
import com.vaadin.ui.CssLayout;
import com.vaadin.ui.DateField;
import com.vaadin.ui.Grid;
import com.vaadin.ui.Grid.Column;
import com.vaadin.ui.Grid.SelectionMode;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Panel;
import com.vaadin.ui.PasswordField;
import com.vaadin.ui.RadioButtonGroup;
import com.vaadin.ui.TextField;
import com.vaadin.ui.UI;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Window;
import com.vaadin.ui.components.grid.MultiSelectionModel;
import com.vaadin.ui.components.grid.MultiSelectionModel.SelectAllCheckBoxVisibility;
import com.vaadin.ui.renderers.HtmlRenderer;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;

/**
 * 用户管理页。
 * <p>
 * 覆盖 {@code users} 表的全部字段：用户名列显示头像与姓名，另有组织、邮箱、手机、
 * 权限标签、状态徽标、创建时间、有效期，以及行内的编辑 / 重置密码 / 禁用启用 / 删除。
 * 顶部是统计卡片与筛选栏，底部是分页与批量操作。
 * <p>
 * 三条与数据一致性有关的约定，改动时务必留意：
 * <ol>
 * <li>登录认证读的是数据库（{@code UserDao}），没有第二份用户来源：历史上的
 * {@code users.properties} 已彻底移除，内置管理员由 {@code DbInitializer} 在启动时幂等写入。</li>
 * <li>"禁用"和"过期"要真正生效，依赖 {@code LoginView} 里的 {@link User#unavailableReason()} 校验。</li>
 * <li>删除与禁用都带保护：不能操作当前登录账号；内置管理员 {@code admin} 永远不能删除、
 * 禁用或降权；也不能让系统失去最后一个拥有全部权限的启用账号。</li>
 * </ol>
 * 样式类统一以 {@code um-} 开头，定义在 {@code styles.css} 末尾的「用户管理页样式」段落。
 */
@Service
@Scope("prototype")
public class UserManagementComponent extends CommonComponent {

	private static final long serialVersionUID = 5187805936625809077L;
	private static final Logger logger = LoggerFactory.getLogger(UserManagementComponent.class);

	// ---------------- 筛选与排序的可选值 ----------------
	private static final String STATUS_ALL = "全部状态";
	private static final String STATUS_AVAILABLE = "正常可用";
	private static final String STATUS_ENABLED = "仅启用";
	private static final String STATUS_DISABLED = "已禁用";
	private static final String STATUS_EXPIRED = "已过期";
	private static final String STATUS_NEAR_EXPIRE = "7 天内到期";
	private static final String PERM_ALL = "全部权限";

	private static final String SORT_NAME_ASC = "用户名 A→Z";
	private static final String SORT_NAME_DESC = "用户名 Z→A";
	private static final String SORT_CREATE_DESC = "创建时间 新→旧";
	private static final String SORT_CREATE_ASC = "创建时间 旧→新";
	private static final String SORT_EXPIRE_ASC = "有效期 快到期的在前";
	private static final String SORT_EXPIRE_DESC = "有效期 长期有效在前";

	private static final String FLAG_ENABLE_TEXT = "启用";
	private static final String FLAG_DISABLE_TEXT = "禁用";

	/** 用户名：字母开头，允许字母数字与 _ . - */
	private static final Pattern PATTERN_USER_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{1,49}$");
	private static final Pattern PATTERN_EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
	private static final Pattern PATTERN_PHONE = Pattern.compile("^1[3-9]\\d{9}$");

	private static final int MIN_PASSWORD_LENGTH = 6;

	// ---------------- UI ----------------
	private Panel mainPanel;
	private VerticalLayout workingAreaLayout;
	private Grid<User> userGrid;

	private Label totalLb;
	private Label availableLb;
	private Label disabledLb;
	private Label expiredLb;

	private TextField keywordField;
	private ComboBox<String> statusBox;
	private ComboBox<String> permissionBox;
	private ComboBox<String> sortBox;

	private Button addBtn;
	private Button batchEnableBtn;
	private Button batchDisableBtn;
	private Button batchDeleteBtn;

	private Label pageInfoLb;
	private Label selectionLb;
	private Button prevBtn;
	private Button nextBtn;
	private ComboBox<Integer> pageSizeBox;

	/** 列标题 -> 列对象，供「列设置」使用（Vaadin 8 的 Grid 没有可枚举列的公开入口，只能自己记） */
	private final Map<String, Column<User, ?>> columnRefs = new LinkedHashMap<String, Column<User, ?>>();

	/** 全量数据；筛选、排序、分页都在内存里做，用户量级不大，没必要每次都查库 */
	private List<User> allUsers = new ArrayList<User>();
	private List<User> viewUsers = new ArrayList<User>();
	private int pageIndex = 0;
	private int pageSize = 10;

	@Autowired
	private UserDao userDao;

	@Override
	public void initLayout() {
		mainPanel = ComponentFactory.getSubPanel();
		// getSubPanel() 默认 850px。但 LogCheckView 的根布局固定 910px 且 overflow:hidden，
		// tab 内容区顶边在 151px，所以 tab 里能看见的高度上限只有 759px 左右。
		// 850px 的面板底边会落到 1001px，被 LogCheckView 裁掉 91px —— 页面底部的
		// 分页/批量按钮行正好落在这一条被裁掉的带子里，看起来就是"按钮消失了"。
		// 压到 745px（底边 896px）留出 14px 余量。
		mainPanel.setHeight("745px");
		setCompositionRoot(mainPanel);
		workingAreaLayout = new VerticalLayout();
		workingAreaLayout.setWidth("100%");
		// 跟着面板内容区走，不要再写死像素：面板高度一变，写死的数字就会顶出去被裁。
		// 网格用 expandRatio 吃掉剩下的空间，底部行自然贴在可见区域的最下沿。
		workingAreaLayout.setHeight("100%");
		workingAreaLayout.setMargin(false);
		workingAreaLayout.setSpacing(false);
		workingAreaLayout.addStyleName("um-root");
		mainPanel.setContent(workingAreaLayout);

		workingAreaLayout.addComponent(buildHeadRow());
		workingAreaLayout.addComponent(buildStatRow());
		workingAreaLayout.addComponent(buildFilterRow());
		buildGrid();
		workingAreaLayout.addComponent(userGrid);
		workingAreaLayout.setExpandRatio(userGrid, 1.0f);
		workingAreaLayout.addComponent(buildFootRow());
	}

	// ==================================================================
	// 顶部：标题与全局操作
	// ==================================================================

	private Component buildHeadRow() {
		CssLayout head = new CssLayout();
		head.addStyleName("um-head");

		Label title = new Label("用户管理");
		title.addStyleName("um-title");
		Label sub = new Label("维护登录账号、权限与有效期");
		sub.addStyleName("um-subtitle");
		VerticalLayout titleBox = new VerticalLayout();
		titleBox.setMargin(false);
		titleBox.setSpacing(false);
		titleBox.addComponents(title, sub);

		addBtn = ComponentFactory.getStandardButton("新增用户");
		addBtn.addStyleName("um-btn-primary");

		CssLayout actions = new CssLayout();
		actions.addStyleName("um-head-actions");
		actions.addComponents(addBtn);

		head.addComponents(titleBox, actions);
		return head;
	}

	// ==================================================================
	// 统计卡片
	// ==================================================================

	private Component buildStatRow() {
		CssLayout row = new CssLayout();
		row.addStyleName("um-stat-row");

		totalLb = new Label("0");
		availableLb = new Label("0");
		disabledLb = new Label("0");
		expiredLb = new Label("0");

		row.addComponents(
				buildStatCard("用户总数", totalLb, "um-stat-total", null),
				buildStatCard("正常可用", availableLb, "um-stat-ok", STATUS_AVAILABLE),
				buildStatCard("已禁用", disabledLb, "um-stat-off", STATUS_DISABLED),
				buildStatCard("已过期", expiredLb, "um-stat-expire", STATUS_EXPIRED));
		return row;
	}

	/**
	 * 一张统计卡片。点击卡片把列表切到对应的筛选条件，再点一次回到全部。
	 */
	private CssLayout buildStatCard(String caption, Label valueLb, String styleName, final String filterValue) {
		CssLayout card = new CssLayout();
		card.addStyleName("um-stat-card");
		card.addStyleName(styleName);
		valueLb.addStyleName("um-stat-value");
		Label capLb = new Label(caption);
		capLb.addStyleName("um-stat-caption");
		card.addComponents(valueLb, capLb);
		if (null != filterValue) {
			card.addStyleName("um-stat-clickable");
			card.addLayoutClickListener(e -> {
				if (filterValue.equals(statusBox.getValue())) {
					statusBox.setValue(STATUS_ALL);
				} else {
					statusBox.setValue(filterValue);
				}
			});
		}
		return card;
	}

	// ==================================================================
	// 筛选栏
	// ==================================================================

	private Component buildFilterRow() {
		CssLayout row = new CssLayout();
		row.addStyleName("um-filter");

		keywordField = ComponentFactory.getStandardTtextField("关键字");
		keywordField.setPlaceholder("用户名 / 姓名 / 组织 / 邮箱");
		keywordField.setWidth("260px");
		// 边输边筛：LAZY 模式配合 350ms 静默期，不会每敲一个键就发一次请求
		keywordField.setValueChangeMode(ValueChangeMode.LAZY);
		keywordField.setValueChangeTimeout(350);
		keywordField.addValueChangeListener(e -> {
			pageIndex = 0;
			refreshView();
		});

		statusBox = ComponentFactory.getStandardComboBox("状态");
		statusBox.setItems(STATUS_ALL, STATUS_AVAILABLE, STATUS_ENABLED, STATUS_DISABLED, STATUS_EXPIRED,
				STATUS_NEAR_EXPIRE);
		statusBox.setEmptySelectionAllowed(false);
		statusBox.setValue(STATUS_ALL);
		statusBox.setWidth("150px");
		statusBox.addValueChangeListener(e -> {
			pageIndex = 0;
			refreshView();
		});

		permissionBox = ComponentFactory.getStandardComboBox("权限");
		permissionBox.setItems(PERM_ALL, "含新增", "含删除", "含修改", "含查询", "无权限");
		permissionBox.setEmptySelectionAllowed(false);
		permissionBox.setValue(PERM_ALL);
		permissionBox.setWidth("140px");
		permissionBox.addValueChangeListener(e -> {
			pageIndex = 0;
			refreshView();
		});

		sortBox = ComponentFactory.getStandardComboBox("排序");
		sortBox.setItems(SORT_NAME_ASC, SORT_NAME_DESC, SORT_CREATE_DESC, SORT_CREATE_ASC, SORT_EXPIRE_ASC,
				SORT_EXPIRE_DESC);
		sortBox.setEmptySelectionAllowed(false);
		sortBox.setValue(SORT_NAME_ASC);
		sortBox.setWidth("170px");
		sortBox.addValueChangeListener(e -> refreshView());

		Button resetBtn = ComponentFactory.getStandardButton("重置筛选");
		resetBtn.addStyleName("um-btn-plain");
		resetBtn.addClickListener(e -> resetFilter());

		row.addComponents(keywordField, statusBox, permissionBox, sortBox, resetBtn);
		return row;
	}

	private void resetFilter() {
		keywordField.setValue("");
		statusBox.setValue(STATUS_ALL);
		permissionBox.setValue(PERM_ALL);
		sortBox.setValue(SORT_NAME_ASC);
		pageIndex = 0;
		refreshView();
	}

	// ==================================================================
	// 表格
	// ==================================================================

	private void buildGrid() {
		userGrid = new Grid<User>();
		userGrid.setWidth("100%");
		// 高度交给外层 VerticalLayout 的 expandRatio 分配，这里写 100% 填满分到的槽位。
		// 曾经写的是 90%，配上同级 expandRatio 后槽位仍是 100%，网格只画到槽位高度的
		// 九成，底部凭空多出一条空隙；写死百分比也和"面板高度会变"这件事相互冲突。
		userGrid.setHeight("100%");
		userGrid.addStyleName("grid_standard");
		userGrid.addStyleName("um-grid");
		userGrid.setBodyRowHeight(46);
		userGrid.setColumnReorderingAllowed(true);
		userGrid.setSelectionMode(SelectionMode.MULTI);
		// 表头带全选复选框，批量操作时比一条条点省事
		if (userGrid.getSelectionModel() instanceof MultiSelectionModel) {
			@SuppressWarnings("unchecked")
			MultiSelectionModel<User> model = (MultiSelectionModel<User>) userGrid.getSelectionModel();
			model.setSelectAllCheckBoxVisibility(SelectAllCheckBoxVisibility.VISIBLE);
		}

		addHtmlColumn(this::htmlUser, "用户名", 220);
		addTextColumn(User::getUserName, "姓名", 100);
		addTextColumn(User::getOrganization, "组织", 140);
		addTextColumn(User::getEmail, "邮箱", 180);
		addTextColumn(User::getCdPhone, "手机", 120);
		addTextColumn(User::permissionText, "权限", 170);
		addHtmlColumn(this::htmlStatus, "状态", 100);
		addTextColumn(UserManagementComponent::textCreateTime, "创建时间", 150);
		addHtmlColumn(this::htmlExpireTime, "有效期", 170);
		addOpColumn();

		userGrid.addSelectionListener(e -> refreshSelectionInfo());
	}

	private Column<User, String> addHtmlColumn(ValueProvider<User, String> provider, String caption, double width) {
		Column<User, String> col = userGrid.addColumn(provider);
		col.setRenderer(new HtmlRenderer());
		col.setCaption(caption);
		col.setWidth(width);
		col.setSortable(false);
		col.setStyleGenerator(u -> u.isEnabled() ? null : "um-row-dim");
		columnRefs.put(caption, col);
		return col;
	}

	private Column<User, String> addTextColumn(ValueProvider<User, String> provider, String caption, double width) {
		Column<User, String> col = userGrid.addColumn(provider);
		col.setCaption(caption);
		col.setWidth(width);
		// 排序交给「排序」下拉做全量排序；表格只显示当前页，列头排序只能排到本页，反而误导
		col.setSortable(false);
		col.setStyleGenerator(u -> u.isEnabled() ? null : "um-row-dim");
		columnRefs.put(caption, col);
		return col;
	}

	private void addOpColumn() {
		Column<User, Component> col = userGrid.addComponentColumn(this::buildOpCell);
		col.setCaption("操作");
		col.setWidth(300);
		col.setSortable(false);
	}

	/**
	 * 行内操作区：编辑 / 重置密码 / 禁用(启用) / 删除。每个按钮各自校验权限。
	 */
	private Component buildOpCell(final User user) {
		CssLayout box = new CssLayout();
		box.addStyleName("um-op-cell");

		Button editBtn = opButton("编辑");
		editBtn.addClickListener(e -> {
			if (!LoginView.checkPermission(Constants.UPDATE)) {
				warn("权限不足，无法修改用户");
				return;
			}
			UI.getCurrent().addWindow(new UserEditWindow(user));
		});

		Button pwdBtn = opButton("重置密码");
		pwdBtn.addClickListener(e -> {
			if (!LoginView.checkPermission(Constants.UPDATE)) {
				warn("权限不足，无法重置密码");
				return;
			}
			UI.getCurrent().addWindow(new ResetPasswordWindow(user));
		});

		box.addComponents(editBtn, pwdBtn);
		if (user.isBuiltinAdmin()) {
			// 内置管理员不给禁用与删除入口：删掉或禁用就等于系统再也没有引导账号了。
			// 按钮的隐藏只是别误导人，真正的硬拦截在 checkProtected 里。
			Label lock = new Label("受保护");
			lock.addStyleName("um-op-lock");
			lock.setDescription("内置管理员，不允许禁用或删除");
			box.addComponent(lock);
			return box;
		}

		Button toggleBtn = opButton(user.isEnabled() ? "禁用" : "启用");
		toggleBtn.addClickListener(e -> {
			if (!LoginView.checkPermission(Constants.UPDATE)) {
				warn("权限不足，无法修改用户状态");
				return;
			}
			toggleEnabled(user);
		});

		Button delBtn = opButton("删除");
		delBtn.addStyleName("um-op-danger");
		delBtn.addClickListener(e -> {
			if (!LoginView.checkPermission(Constants.DELETE)) {
				warn("权限不足，无法删除用户");
				return;
			}
			List<User> targets = new ArrayList<User>();
			targets.add(user);
			confirmDelete(targets);
		});

		box.addComponents(toggleBtn, delBtn);
		return box;
	}

	private Button opButton(String caption) {
		Button btn = new Button(caption);
		btn.addStyleName("um-op-btn");
		return btn;
	}

	// ==================================================================
	// 底部：分页与批量操作
	// ==================================================================

	private Component buildFootRow() {
		CssLayout foot = new CssLayout();
		foot.addStyleName("um-foot");

		prevBtn = ComponentFactory.getStandardButton("上一页");
		prevBtn.addStyleName("um-btn-plain");
		nextBtn = ComponentFactory.getStandardButton("下一页");
		nextBtn.addStyleName("um-btn-plain");
		prevBtn.addClickListener(e -> {
			if (pageIndex > 0) {
				pageIndex--;
				renderPage();
			}
		});
		nextBtn.addClickListener(e -> {
			if (pageIndex < totalPages() - 1) {
				pageIndex++;
				renderPage();
			}
		});

		pageInfoLb = new Label("");
		pageInfoLb.addStyleName("um-page-info");

		pageSizeBox = ComponentFactory.getStandardComboBox("每页");
		pageSizeBox.setItems(10, 20, 50, 100);
		pageSizeBox.setEmptySelectionAllowed(false);
		pageSizeBox.setValue(pageSize);
		pageSizeBox.setWidth("120px");
		pageSizeBox.addValueChangeListener(e -> {
			Integer value = pageSizeBox.getValue();
			if (null != value) {
				pageSize = value;
				pageIndex = 0;
				renderPage();
			}
		});

		CssLayout pager = new CssLayout();
		pager.addStyleName("um-pager");
		pager.addComponents(prevBtn, nextBtn, pageSizeBox, pageInfoLb);

		selectionLb = new Label("未选择");
		selectionLb.addStyleName("um-selection");

		batchEnableBtn = ComponentFactory.getStandardButton("批量启用");
		batchEnableBtn.addStyleName("um-btn-green");
		batchDisableBtn = ComponentFactory.getStandardButton("批量禁用");
		batchDisableBtn.addStyleName("um-btn-plain");
		batchDeleteBtn = ComponentFactory.getStandardButton("批量删除");
		batchDeleteBtn.addStyleName("um-btn-red");

		batchEnableBtn.addClickListener(e -> batchToggle(true));
		batchDisableBtn.addClickListener(e -> batchToggle(false));
		batchDeleteBtn.addClickListener(e -> {
			if (!LoginView.checkPermission(Constants.DELETE)) {
				warn("权限不足，无法删除用户");
				return;
			}
			List<User> selected = selectedUsers();
			if (selected.isEmpty()) {
				warn("请先勾选要删除的用户");
				return;
			}
			confirmDelete(selected);
		});

		CssLayout batchBox = new CssLayout();
		batchBox.addStyleName("um-batch");
		batchBox.addComponents(selectionLb, batchEnableBtn, batchDisableBtn, batchDeleteBtn);

		foot.addComponents(pager, batchBox);
		return foot;
	}

	// ==================================================================
	// 数据加载与渲染
	// ==================================================================

	@Override
	public void initContent() {
		reloadData();
	}

	/**
	 * 重新从数据库读取全量用户
	 */
	private void reloadData() {
		List<User> loaded = userDao.selectList(new QueryWrapper<User>());
		allUsers = null == loaded ? new ArrayList<User>() : loaded;
		refreshView();
	}

	/**
	 * 按当前筛选条件重算视图并渲染
	 */
	private void refreshView() {
		viewUsers = applyFilter(allUsers);
		sortView();
		if (pageIndex > totalPages() - 1) {
			pageIndex = Math.max(0, totalPages() - 1);
		}
		updateStat();
		renderPage();
	}

	private List<User> applyFilter(List<User> source) {
		String keyword = StrUtil.trim(keywordField.getValue()).toLowerCase();
		String status = statusBox.getValue();
		String permission = permissionBox.getValue();
		List<User> result = new ArrayList<User>();
		for (User user : source) {
			if (!matchKeyword(user, keyword)) {
				continue;
			}
			if (!matchStatus(user, status)) {
				continue;
			}
			if (!matchPermission(user, permission)) {
				continue;
			}
			result.add(user);
		}
		return result;
	}

	private boolean matchKeyword(User user, String keyword) {
		if (StrUtil.isBlank(keyword)) {
			return true;
		}
		return contains(user.getUserId(), keyword)
				|| contains(user.getUserName(), keyword)
				|| contains(user.getOrganization(), keyword)
				|| contains(user.getEmail(), keyword)
				|| contains(user.getCdPhone(), keyword);
	}

	private boolean contains(String text, String keyword) {
		return null != text && text.toLowerCase().contains(keyword);
	}

	private boolean matchStatus(User user, String status) {
		if (StrUtil.isBlank(status) || STATUS_ALL.equals(status)) {
			return true;
		}
		if (STATUS_AVAILABLE.equals(status)) {
			return user.isAvailable();
		}
		if (STATUS_ENABLED.equals(status)) {
			return user.isEnabled();
		}
		if (STATUS_DISABLED.equals(status)) {
			return !user.isEnabled();
		}
		if (STATUS_EXPIRED.equals(status)) {
			return user.isExpired();
		}
		if (STATUS_NEAR_EXPIRE.equals(status)) {
			return user.isNearExpire();
		}
		return true;
	}

	private boolean matchPermission(User user, String permission) {
		if (StrUtil.isBlank(permission) || PERM_ALL.equals(permission)) {
			return true;
		}
		Set<String> items = user.permissionSet();
		if ("无权限".equals(permission)) {
			return items.isEmpty();
		}
		if ("含新增".equals(permission)) {
			return items.contains(Constants.ADD) || items.contains(Constants.ALL);
		}
		if ("含删除".equals(permission)) {
			return items.contains(Constants.DELETE) || items.contains(Constants.ALL);
		}
		if ("含修改".equals(permission)) {
			return items.contains(Constants.UPDATE) || items.contains(Constants.ALL);
		}
		if ("含查询".equals(permission)) {
			return items.contains(Constants.QUERY) || items.contains(Constants.ALL);
		}
		return true;
	}

	private void sortView() {
		viewUsers.sort(comparatorFor(sortBox.getValue()));
	}

	private static Comparator<User> comparatorFor(String sort) {
		if (SORT_NAME_DESC.equals(sort)) {
			return (a, b) -> StrUtil.nullToEmpty(b.getUserId()).compareTo(StrUtil.nullToEmpty(a.getUserId()));
		}
		if (SORT_CREATE_DESC.equals(sort)) {
			return (a, b) -> dateAscNullLast(b.getCreateTime(), a.getCreateTime());
		}
		if (SORT_CREATE_ASC.equals(sort)) {
			return (a, b) -> dateAscNullLast(a.getCreateTime(), b.getCreateTime());
		}
		if (SORT_EXPIRE_ASC.equals(sort)) {
			// 永久有效的账号（expireTime 为空）排在最后
			return (a, b) -> dateAscNullLast(a.getExpireTime(), b.getExpireTime());
		}
		if (SORT_EXPIRE_DESC.equals(sort)) {
			return (a, b) -> dateAscNullFirst(a.getExpireTime(), b.getExpireTime());
		}
		return (a, b) -> StrUtil.nullToEmpty(a.getUserId()).compareTo(StrUtil.nullToEmpty(b.getUserId()));
	}

	/** 日期升序比较，null 视为最大排在最后 */
	private static int dateAscNullLast(Date d1, Date d2) {
		if (null == d1 && null == d2) {
			return 0;
		}
		if (null == d1) {
			return 1;
		}
		if (null == d2) {
			return -1;
		}
		return d1.compareTo(d2);
	}

	/** 日期升序比较，null 排在最前（用于"长期有效在前"） */
	private static int dateAscNullFirst(Date d1, Date d2) {
		if (null == d1 && null == d2) {
			return 0;
		}
		if (null == d1) {
			return -1;
		}
		if (null == d2) {
			return 1;
		}
		return d1.compareTo(d2);
	}

	private int totalPages() {
		if (viewUsers.isEmpty()) {
			return 1;
		}
		return (viewUsers.size() + pageSize - 1) / pageSize;
	}

	private void renderPage() {
		int from = pageIndex * pageSize;
		int to = Math.min(from + pageSize, viewUsers.size());
		List<User> pageItems = from >= to ? new ArrayList<User>()
				: new ArrayList<User>(viewUsers.subList(from, to));
		userGrid.setItems(pageItems);
		userGrid.getSelectionModel().deselectAll();
		refreshSelectionInfo();
		pageInfoLb.setValue(String.format("共 %d 条，第 %d/%d 页", viewUsers.size(), pageIndex + 1, totalPages()));
		prevBtn.setEnabled(pageIndex > 0);
		nextBtn.setEnabled(pageIndex < totalPages() - 1);
	}

	private void refreshSelectionInfo() {
		if (null == selectionLb) {
			return;
		}
		int size = userGrid.getSelectedItems().size();
		selectionLb.setValue(size == 0 ? "未选择" : "已选 " + size + " 项");
		boolean hasSelection = size > 0;
		batchEnableBtn.setEnabled(hasSelection);
		batchDisableBtn.setEnabled(hasSelection);
		batchDeleteBtn.setEnabled(hasSelection);
	}

	private void updateStat() {
		Date now = new Date();
		int available = 0;
		int disabled = 0;
		int expired = 0;
		for (User user : allUsers) {
			if (!user.isEnabled()) {
				disabled++;
			}
			if (user.isExpiredAt(now)) {
				expired++;
			}
			if (user.isAvailable()) {
				available++;
			}
		}
		totalLb.setValue(String.valueOf(allUsers.size()));
		availableLb.setValue(String.valueOf(available));
		disabledLb.setValue(String.valueOf(disabled));
		expiredLb.setValue(String.valueOf(expired));
	}

	// ==================================================================
	// 单元格 HTML 渲染
	// ==================================================================

	private String htmlUser(User user) {
		String name = StrUtil.isBlank(user.getUserName()) ? "未填写姓名" : user.getUserName();
		String id = StrUtil.nullToEmpty(user.getUserId());
		String initial = id.isEmpty() ? "?" : id.substring(0, 1).toUpperCase();
		StringBuilder buf = new StringBuilder();
		buf.append("<div class=\"um-user-cell\">");
		buf.append("<span class=\"um-avatar\">").append(escape(initial)).append("</span>");
		buf.append("<span class=\"um-user-text\">");
		buf.append("<span class=\"um-user-id\">").append(escape(id));
		if (user.isAllPermission()) {
			buf.append("<span class=\"um-tag-admin\">管理员</span>");
		}
		buf.append("</span>");
		buf.append("<span class=\"um-user-name\">").append(escape(name)).append("</span>");
		buf.append("</span></div>");
		return buf.toString();
	}

	private String htmlStatus(User user) {
		String cls;
		if (!user.isEnabled()) {
			cls = "um-badge-off";
		} else if (user.isExpired()) {
			cls = "um-badge-expired";
		} else if (user.isNearExpire()) {
			cls = "um-badge-warn";
		} else {
			cls = "um-badge-ok";
		}
		return "<span class=\"um-badge " + cls + "\">" + user.statusText() + "</span>";
	}

	private String htmlExpireTime(User user) {
		if (null == user.getExpireTime()) {
			return "<span class=\"um-expire-forever\">长期有效</span>";
		}
		String date = Util.formatDate(user.getExpireTime());
		if (user.isExpired()) {
			return "<span class=\"um-expire\">" + date + "</span><span class=\"um-expire-hint\">已过期</span>";
		}
		Integer days = user.remainDays();
		String hint = null == days ? "" : (days == 0 ? "今天到期" : "剩 " + days + " 天");
		if (user.isNearExpire()) {
			return "<span class=\"um-expire-warn\">" + date + "</span><span class=\"um-expire-hint-warn\">" + hint
					+ "</span>";
		}
		return "<span class=\"um-expire\">" + date + "</span><span class=\"um-expire-hint\">" + hint + "</span>";
	}

	private static String textCreateTime(User user) {
		return null == user.getCreateTime() ? "—" : Util.formatDateTime(user.getCreateTime());
	}

	private String escape(String text) {
		return null == text ? "" : HtmlUtil.escape(text);
	}

	// ==================================================================
	// 交互逻辑
	// ==================================================================

	@Override
	public void registerHandler() {
		addBtn.addClickListener(new ClickListener() {
			private static final long serialVersionUID = 1L;

			@Override
			public void buttonClick(ClickEvent event) {
				if (!LoginView.checkPermission(Constants.ADD)) {
					warn("权限不足，无法新增用户");
					return;
				}
				UI.getCurrent().addWindow(new UserEditWindow(null));
			}
		});

	}

	/**
	 * 当前登录用户，用于自我保护判断
	 */
	private User currentUser() {
		return (User) VaadinSession.getCurrent().getAttribute("user");
	}

	/**
	 * 内置管理员相关的保护判定。与当前会话、与列表内容都无关，因此独立成静态方法，
	 * 以便脱离 Vaadin 环境单独验证。返回拒绝原因，允许时返回 null。
	 */
	static String builtinAdminBlockReason(List<User> targets, boolean removing) {
		if (null == targets) {
			return null;
		}
		for (User target : targets) {
			if (target.isBuiltinAdmin()) {
				return removing ? "内置管理员 " + User.ADMIN_USER_ID + " 不允许删除"
						: "内置管理员 " + User.ADMIN_USER_ID + " 不允许禁用";
			}
		}
		return null;
	}

	/**
	 * 校验一批用户能否被改状态或删除，返回拒绝原因，全部允许时返回 null。
	 * <p>
	 * 三条硬约束：内置管理员不可删不可禁用；不能动自己；不能让系统失去最后一个
	 * 拥有全部权限的启用账号，否则改完就再也登不进来了。
	 */
	private String checkProtected(List<User> targets, boolean removing) {
		String adminReason = builtinAdminBlockReason(targets, removing);
		if (null != adminReason) {
			return adminReason;
		}
		User current = currentUser();
		String currentId = null == current ? null : current.getUserId();
		if (StrUtil.isBlank(currentId)) {
			// 会话里取不到当前用户时宁可不放行：自我保护的判断依据都缺了，
			// 继续执行等于"谁都能删"。
			return "无法确认当前登录账号，出于安全考虑已阻止本次操作";
		}
		boolean touchSelf = false;
		boolean touchLastAdmin = false;
		int adminCount = countEnabledAdmins();

		for (User target : targets) {
			if (currentId.equals(target.getUserId())) {
				touchSelf = true;
			}
			if (target.isAllPermission() && target.isEnabled() && adminCount <= 1) {
				touchLastAdmin = true;
			}
		}
		if (touchSelf) {
			return removing ? "不能删除当前登录的账号" : "不能修改当前登录账号的状态";
		}
		if (touchLastAdmin) {
			return removing ? "这是最后一个拥有全部权限的启用账号，删除后将无法登录，已阻止"
					: "这是最后一个拥有全部权限的启用账号，禁用后将无法登录，已阻止";
		}
		return null;
	}

	private int countEnabledAdmins() {
		int count = 0;
		for (User user : allUsers) {
			if (user.isAllPermission() && user.isAvailable()) {
				count++;
			}
		}
		return count;
	}

	private List<User> selectedUsers() {
		return new ArrayList<User>(userGrid.getSelectedItems());
	}

	private void batchToggle(boolean enable) {
		if (!LoginView.checkPermission(Constants.UPDATE)) {
			warn("权限不足，无法修改用户状态");
			return;
		}
		List<User> selected = selectedUsers();
		if (selected.isEmpty()) {
			warn("请先勾选要操作的用户");
			return;
		}
		if (!enable) {
			String reason = checkProtected(selected, false);
			if (null != reason) {
				warn(reason);
				return;
			}
		}
		int changed = 0;
		for (User user : selected) {
			if (user.isEnabled() == enable) {
				continue;
			}
			User db = userDao.selectById(user.getUserId());
			if (null == db) {
				continue;
			}
			db.setUserFlag(enable ? User.FLAG_ENABLED : User.FLAG_DISABLED);
			userDao.updateById(db);
			changed++;
		}
		reloadData();
		info(changed == 0 ? "所选账号状态无需变更" : "已" + (enable ? "启用" : "禁用") + " " + changed + " 个账号");
	}

	/**
	 * 单个账号启用 / 禁用
	 */
	private void toggleEnabled(User user) {
		boolean enable = !user.isEnabled();
		if (!enable) {
			List<User> targets = new ArrayList<User>();
			targets.add(user);
			String reason = checkProtected(targets, false);
			if (null != reason) {
				warn(reason);
				return;
			}
		}
		User db = userDao.selectById(user.getUserId());
		if (null == db) {
			warn("用户不存在，可能已被其他人删除");
			reloadData();
			return;
		}
		db.setUserFlag(enable ? User.FLAG_ENABLED : User.FLAG_DISABLED);
		userDao.updateById(db);
		reloadData();
		info("已" + (enable ? "启用" : "禁用") + "账号 " + user.getUserId());
	}

	/**
	 * 删除确认
	 */
	private void confirmDelete(final List<User> targets) {
		String reason = checkProtected(targets, true);
		if (null != reason) {
			warn(reason);
			return;
		}
		StringBuilder buf = new StringBuilder();
		buf.append("确定要删除以下 ").append(targets.size()).append(" 个账号吗？<br/><br/>");
		buf.append("<span style='color:#f56c6c;'>");
		int limit = Math.min(targets.size(), 8);
		for (int i = 0; i < limit; i++) {
			if (i > 0) {
				buf.append("、");
			}
			buf.append(escape(targets.get(i).getUserId()));
		}
		if (targets.size() > limit) {
			buf.append(" 等 ").append(targets.size()).append(" 个");
		}
		buf.append("</span><br/><br/>删除后账号将无法登录，且不可恢复。");

		ConfirmationDialogPopupWindow win = new ConfirmationDialogPopupWindow("删除用户", buf.toString(), "确认删除",
				"取消", true);
		win.getYesButton().addClickListener(e -> {
			win.close();
			deleteUsers(targets);
		});
		win.showConfirmation();
	}

	/**
	 * 真正的删除，只落库。
	 * <p>
	 * 这里是所有删除路径的终点，所以保护判定在这里再兜一次底：{@code confirmDelete}
	 * 虽然已经校验过，但"不该删的删不掉"这件事不能依赖调用方记得先校验。
	 */
	private void deleteUsers(List<User> targets) {
		List<String> ids = new ArrayList<String>();
		for (User user : targets) {
			ids.add(user.getUserId());
		}
		String reason = checkProtected(targets, true);
		if (null != reason) {
			logger.warn("已阻止删除操作：{}，目标 {}", reason, ids);
			warn(reason);
			reloadData();
			return;
		}
		int affected = userDao.deleteBatchIds(ids);
		logger.info("删除用户 {} 个：{}", affected, ids);
		reloadData();
		info("已删除 " + affected + " 个账号");
	}

	private void info(String message) {
		Notification.show(message, Notification.Type.HUMANIZED_MESSAGE);
	}

	private void warn(String message) {
		Notification.show(message, Notification.Type.WARNING_MESSAGE);
	}

	// ==================================================================
	// 新增 / 编辑窗口
	// ==================================================================

	/**
	 * 新增与编辑共用的窗口，传入 null 表示新增。
	 */
	class UserEditWindow extends Window {

		private static final long serialVersionUID = 3394269109511557536L;

		private TextField userIdField;
		private TextField nameField;
		private TextField orgField;
		private TextField emailField;
		private TextField phoneField;
		private PasswordField passwordField;
		private PasswordField confirmField;
		private RadioButtonGroup<String> flagGroup;
		private DateField expireField;
		private ComboBoxMultiselect<String> permissionBox;

		UserEditWindow(final User editing) {
			super(null == editing ? "新增用户" : "编辑用户 " + editing.getUserId());
			center();
			setModal(true);
			setClosable(true);
			setResizable(false);
			setWidth("680px");

			VerticalLayout root = new VerticalLayout();
			root.addStyleName("um-window");
			root.setMargin(true);
			root.setSpacing(false);
			root.setWidth("100%");

			userIdField = ComponentFactory.getStandardTtextField("用户名 *");
			userIdField.setPlaceholder("字母开头，2-50 位字母数字或 _ . -");
			userIdField.setWidth("100%");

			nameField = ComponentFactory.getStandardTtextField("姓名");
			nameField.setPlaceholder("真实姓名");
			nameField.setWidth("100%");

			orgField = ComponentFactory.getStandardTtextField("所属组织");
			orgField.setPlaceholder("部门或公司");
			orgField.setWidth("100%");

			emailField = ComponentFactory.getStandardTtextField("邮箱");
			emailField.setPlaceholder("name@example.com");
			emailField.setWidth("100%");

			phoneField = ComponentFactory.getStandardTtextField("手机号");
			phoneField.setPlaceholder("11 位手机号");
			phoneField.setWidth("100%");

			passwordField = ComponentFactory.getStandardPassedwordField("密码 *");
			passwordField.setPlaceholder("不少于 " + MIN_PASSWORD_LENGTH + " 位");
			passwordField.setWidth("100%");

			confirmField = ComponentFactory.getStandardPassedwordField("确认密码 *");
			confirmField.setPlaceholder("再输入一次");
			confirmField.setWidth("100%");

			List<String> flagItems = new ArrayList<String>();
			flagItems.add(FLAG_ENABLE_TEXT);
			flagItems.add(FLAG_DISABLE_TEXT);
			flagGroup = ComponentFactory.getStandardRadioButtonGroup(flagItems);
			flagGroup.setCaption("状态");
			flagGroup.setValue(FLAG_ENABLE_TEXT);
			flagGroup.setWidth("100%");

			expireField = ComponentFactory.getStandardDateField();
			expireField.setCaption("有效期至");
			expireField.setWidth("100%");
			expireField.setPlaceholder("留空表示长期有效");
			expireField.setRangeStart(LocalDate.of(2000, 1, 1));

			permissionBox = ComponentFactory.getComboxMultiselect("权限 *");
			permissionBox.setItems(Constants.ADD, Constants.DELETE, Constants.UPDATE, Constants.QUERY,
					Constants.UPLOAD, Constants.ALL);
			permissionBox.setWidth("100%");
			permissionBox.showSelectAllButton(true);
			permissionBox.showClearButton(true);
			permissionBox.setSelectAllButtonCaption("全选");
			permissionBox.setClearButtonCaption("清空");
			permissionBox.setPlaceholder("选择权限");

			root.addComponent(formRow(userIdField, nameField));
			root.addComponent(formRow(orgField, emailField));
			root.addComponent(formRow(phoneField, expireField));
			root.addComponent(formRow(passwordField, confirmField));
			root.addComponent(formRow(flagGroup, permissionBox));

			Label hint = new Label(null == editing
					? "用户名创建后不可修改；权限决定该账号能看到和操作哪些功能。"
					: (editing.isBuiltinAdmin()
							? "内置管理员 " + User.ADMIN_USER_ID + "：状态 / 权限 / 有效期已锁定，仅可修改资料与密码。"
							: "用户名不可修改；密码留空表示不改动。"));
			hint.addStyleName("um-window-hint");
			root.addComponent(hint);

			HorizontalLayout buttons = new HorizontalLayout();
			buttons.addStyleName("um-window-buttons");
			buttons.setWidth("100%");
			Button saveBtn = ComponentFactory.getStandardButton(null == editing ? "创建" : "保存");
			saveBtn.addStyleName("um-btn-primary");
			Button cancelBtn = ComponentFactory.getStandardButton("取消");
			cancelBtn.addStyleName("um-btn-plain");
			buttons.addComponents(cancelBtn, saveBtn);
			buttons.setComponentAlignment(cancelBtn, Alignment.MIDDLE_RIGHT);
			buttons.setComponentAlignment(saveBtn, Alignment.MIDDLE_RIGHT);
			buttons.setExpandRatio(cancelBtn, 1.0f);
			root.addComponent(buttons);

			if (null != editing) {
				fillForm(editing);
			} else {
				permissionBox.select(Constants.QUERY);
			}
			cancelBtn.addClickListener(e -> close());
			saveBtn.addClickListener(e -> save(editing));

			setContent(root);
			userIdField.focus();
		}

		private void fillForm(User user) {
			userIdField.setValue(user.getUserId());
			// 主键不允许改，否则等于新建一个账号，而旧账号还留在库里
			userIdField.setEnabled(false);
			nameField.setValue(StrUtil.nullToEmpty(user.getUserName()));
			orgField.setValue(StrUtil.nullToEmpty(user.getOrganization()));
			emailField.setValue(StrUtil.nullToEmpty(user.getEmail()));
			phoneField.setValue(StrUtil.nullToEmpty(user.getCdPhone()));
			expireField.setValue(toLocalDate(user.getExpireTime()));
			flagGroup.setValue(user.isEnabled() ? FLAG_ENABLE_TEXT : FLAG_DISABLE_TEXT);
			for (String item : user.permissionSet()) {
				permissionBox.select(item);
			}
			passwordField.setCaption("密码");
			confirmField.setCaption("确认密码");
			if (user.isBuiltinAdmin()) {
				// 先清空有效期再禁用：被禁用的控件不会再提交新值，服务端保留的就是此刻的状态
				expireField.setValue(null);
				expireField.setEnabled(false);
				flagGroup.setEnabled(false);
				permissionBox.setEnabled(false);
			}
		}

		private void save(User editing) {
			String userId = StrUtil.trim(userIdField.getValue());
			String validation = validate(userId, editing);
			if (null != validation) {
				warn(validation);
				return;
			}
			String permission = buildPermission();
			if (StrUtil.isBlank(permission)) {
				warn("请至少选择一项权限");
				return;
			}
			String flag = FLAG_DISABLE_TEXT.equals(flagGroup.getValue()) ? User.FLAG_DISABLED : User.FLAG_ENABLED;

			if (null == editing) {
				if (null != userDao.selectById(userId)) {
					warn("用户名已存在，请换一个");
					return;
				}
				User user = new User();
				user.setUserId(userId);
				user.setUserName(StrUtil.trim(nameField.getValue()));
				user.setOrganization(StrUtil.trim(orgField.getValue()));
				user.setEmail(StrUtil.trim(emailField.getValue()));
				user.setCdPhone(StrUtil.trim(phoneField.getValue()));
				user.setPassword(Util.getSm3DigestStr(passwordField.getValue()));
				user.setPermission(permission);
				user.setUserFlag(flag);
				user.setCreateTime(new Date());
				user.setExpireTime(toDate(expireField.getValue()));
				userDao.insert(user);
				logger.info("新增用户 {}", userId);
				close();
				reloadData();
				info("用户 " + userId + " 创建成功");
				return;
			}

			User db = userDao.selectById(editing.getUserId());
			if (null == db) {
				warn("用户不存在，可能已被其他人删除");
				close();
				reloadData();
				return;
			}
			if (User.FLAG_DISABLED.equals(flag)) {
				List<User> targets = new ArrayList<User>();
				targets.add(db);
				String reason = checkProtected(targets, false);
				if (null != reason) {
					warn(reason);
					return;
				}
			}
			db.setUserName(StrUtil.trim(nameField.getValue()));
			db.setOrganization(StrUtil.trim(orgField.getValue()));
			db.setEmail(StrUtil.trim(emailField.getValue()));
			db.setCdPhone(StrUtil.trim(phoneField.getValue()));
			if (db.isBuiltinAdmin()) {
				// 表单控件已经禁用，这里再钉死一次：内置管理员的权限、状态、有效期
				// 不允许被任何路径改掉，否则系统会失去唯一的引导账号
				db.setPermission(Constants.ALL);
				db.setUserFlag(User.FLAG_ENABLED);
				db.setExpireTime(null);
			} else {
				db.setPermission(permission);
				db.setUserFlag(flag);
				db.setExpireTime(toDate(expireField.getValue()));
			}
			// 密码留空即不改动：直接复用库里读出来的密文
			if (StrUtil.isNotBlank(passwordField.getValue())) {
				db.setPassword(Util.getSm3DigestStr(passwordField.getValue()));
			}
			userDao.updateById(db);
			logger.info("修改用户 {}", db.getUserId());
			close();
			reloadData();
			info("用户 " + db.getUserId() + " 已保存");
		}

		private String buildPermission() {
			Set<String> selected = permissionBox.getValue();
			if (null == selected || selected.isEmpty()) {
				return "";
			}
			StringBuilder buf = new StringBuilder();
			for (Object item : selected) {
				buf.append(item).append(",");
			}
			return buf.toString();
		}

		/**
		 * 表单校验。返回错误提示，全部通过返回 null。
		 */
		private String validate(String userId, User editing) {
			if (StrUtil.isBlank(userId)) {
				return "请输入用户名";
			}
			if (null == editing && !PATTERN_USER_ID.matcher(userId).matches()) {
				return "用户名需以字母开头，长度 2-50 位，只能包含字母、数字与 _ . -";
			}
			if (StrUtil.isNotBlank(emailField.getValue())
					&& !PATTERN_EMAIL.matcher(StrUtil.trim(emailField.getValue())).matches()) {
				return "邮箱格式不正确";
			}
			if (StrUtil.isNotBlank(phoneField.getValue())
					&& !PATTERN_PHONE.matcher(StrUtil.trim(phoneField.getValue())).matches()) {
				return "手机号应为 11 位，且以 1 开头";
			}
			String pwd = passwordField.getValue();
			if (null == editing && StrUtil.isBlank(pwd)) {
				return "请输入密码";
			}
			if (StrUtil.isNotBlank(pwd)) {
				if (pwd.length() < MIN_PASSWORD_LENGTH) {
					return "密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位";
				}
				if (!pwd.equals(confirmField.getValue())) {
					return "两次输入的密码不一致";
				}
			}
			return null;
		}
	}

	/**
	 * Date 转 LocalDate。Vaadin 8.16 的 DateField 用的是 java.time.LocalDate，
	 * 而实体与数据库里都是 java.util.Date，两侧的转换集中在这里。
	 */
	private static LocalDate toLocalDate(Date date) {
		return null == date ? null : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
	}

	/**
	 * LocalDate 转 Date，取当天零点。存库时由 TextDateTypeHandler 统一成
	 * yyyy-MM-dd 00:00:00，"当天有效"的语义由 User.isExpiredAt 按当天 23:59:59 判断。
	 */
	private static Date toDate(LocalDate localDate) {
		return null == localDate ? null
				: Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
	}

	/**
	 * 一行两列。
	 * <p>
	 * 每一列外面必须再包一层 {@link CssLayout}：Vaadin 8 的 CssLayout 不套 {@code v-slot}，
	 * 也不套 {@code v-captionwrapper}，而是把带标题组件的标题当作**独立子元素**插到控件前面
	 * （见客户端 {@code CssLayoutConnector.updateCaption()} 里的
	 * {@code getWidget().insert(caption, widgetPosition)}）。所以直接把字段塞进这一行，
	 * DOM 是 {@code [标题][控件][标题][控件]} 四个元素，行上的 {@code flex: 1 1 0}
	 * 会按四个子元素平分宽度：标题吃掉一半，输入框被压成四分之一，左边缘还跟着标题字数漂，
	 * 看起来就是"输入框没对齐"。包一层之后行里只有两个子元素，列内再按正常文档流排标题与控件。
	 */
	private CssLayout formRow(Component left, Component right) {
		CssLayout row = new CssLayout();
		row.addStyleName("um-form-row");
		row.addComponents(formCol(left), formCol(right));
		return row;
	}

	/** 表单里的一列。宽度由外层 {@code .um-form-row} 两等分，这里只负责装标题与控件。 */
	private CssLayout formCol(Component field) {
		CssLayout col = new CssLayout();
		col.addStyleName("um-form-col");
		col.addComponent(field);
		return col;
	}

	// ==================================================================
	// 重置密码窗口
	// ==================================================================

	class ResetPasswordWindow extends Window {

		private static final long serialVersionUID = 7751305470339621975L;

		ResetPasswordWindow(final User user) {
			super("重置密码 - " + user.getUserId());
			center();
			setModal(true);
			setClosable(true);
			setResizable(false);
			setWidth("480px");

			VerticalLayout root = new VerticalLayout();
			root.addStyleName("um-window");
			root.setMargin(true);
			root.setSpacing(false);
			root.setWidth("100%");

			final PasswordField newPwd = ComponentFactory.getStandardPassedwordField("新密码 *");
			newPwd.setPlaceholder("不少于 " + MIN_PASSWORD_LENGTH + " 位");
			newPwd.setWidth("100%");
			final PasswordField confirmPwd = ComponentFactory.getStandardPassedwordField("确认新密码 *");
			confirmPwd.setPlaceholder("再输入一次");
			confirmPwd.setWidth("100%");

			final TextField generatedField = ComponentFactory.getStandardTtextField("随机密码（点生成后可直接复制）");
			generatedField.setWidth("100%");

			Button genBtn = ComponentFactory.getStandardButton("生成随机密码");
			genBtn.addStyleName("um-btn-plain");
			genBtn.addClickListener(e -> {
				// 去掉容易混淆的 0/O/1/l/I，方便口头转述或手抄
				String random = RandomUtil.randomString(
						"abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789", 10);
				generatedField.setValue(random);
				newPwd.setValue(random);
				confirmPwd.setValue(random);
			});

			Label hint = new Label("重置后该账号的旧密码立即失效，请把新密码告知使用者。");
			hint.addStyleName("um-window-hint");

			HorizontalLayout buttons = new HorizontalLayout();
			buttons.addStyleName("um-window-buttons");
			buttons.setWidth("100%");
			Button saveBtn = ComponentFactory.getStandardButton("确认重置");
			saveBtn.addStyleName("um-btn-primary");
			Button cancelBtn = ComponentFactory.getStandardButton("取消");
			cancelBtn.addStyleName("um-btn-plain");
			buttons.addComponents(cancelBtn, saveBtn);
			buttons.setComponentAlignment(cancelBtn, Alignment.MIDDLE_RIGHT);
			buttons.setComponentAlignment(saveBtn, Alignment.MIDDLE_RIGHT);
			buttons.setExpandRatio(cancelBtn, 1.0f);

			root.addComponents(newPwd, confirmPwd, generatedField, genBtn, hint, buttons);

			cancelBtn.addClickListener(e -> close());
			saveBtn.addClickListener(e -> {
				String pwd = newPwd.getValue();
				if (StrUtil.isBlank(pwd) || pwd.length() < MIN_PASSWORD_LENGTH) {
					warn("新密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
					return;
				}
				if (!pwd.equals(confirmPwd.getValue())) {
					warn("两次输入的密码不一致");
					return;
				}
				User db = userDao.selectById(user.getUserId());
				if (null == db) {
					warn("用户不存在，可能已被其他人删除");
					close();
					reloadData();
					return;
				}
				db.setPassword(Util.getSm3DigestStr(pwd));
				userDao.updateById(db);
				logger.info("重置用户 {} 的密码", db.getUserId());
				close();
				reloadData();
				info("已重置 " + db.getUserId() + " 的密码");
			});

			setContent(root);
			newPwd.focus();
		}
	}

	// ==================================================================
	// 列设置窗口
	// ==================================================================

	class ColumnSettingWindow extends Window {

		private static final long serialVersionUID = -2011730313645027824L;

		ColumnSettingWindow() {
			super("列设置");
			center();
			setModal(true);
			setClosable(true);
			setResizable(false);
			setWidth("440px");

			VerticalLayout root = new VerticalLayout();
			root.addStyleName("um-window");
			root.setMargin(true);
			root.setWidth("100%");

			Label hint = new Label("勾选需要显示的列。隐藏只是不占地方，数据不会丢。");
			hint.addStyleName("um-window-hint");

			final CheckBoxGroup<String> group = new CheckBoxGroup<String>("显示的列");
			List<String> visible = new ArrayList<String>();
			for (Map.Entry<String, Column<User, ?>> entry : columnRefs.entrySet()) {
				if (!entry.getValue().isHidden()) {
					visible.add(entry.getKey());
				}
			}
			group.setItems(new ArrayList<String>(columnRefs.keySet()));
			group.setValue(new LinkedHashSet<String>(visible));
			group.addStyleName("um-column-group");
			group.addValueChangeListener(e -> {
				Set<String> selected = group.getValue();
				for (Map.Entry<String, Column<User, ?>> entry : columnRefs.entrySet()) {
					entry.getValue().setHidden(null == selected || !selected.contains(entry.getKey()));
				}
			});

			Button closeBtn = ComponentFactory.getStandardButton("关闭");
			closeBtn.addStyleName("um-btn-plain");
			closeBtn.addClickListener(e -> close());

			root.addComponents(hint, group, closeBtn);
			setContent(root);
		}
	}

	// ==================================================================
	// Excel 导出
	// ==================================================================

	/**
	 * 导出当前筛选结果。导出的是"当前看到的这些"而不是全表，筛完直接导出就能拿到想要的子集。
	 */
	class UserExcelStreamSource implements StreamResource.StreamSource {

		private static final long serialVersionUID = 5577184501710933374L;

		@Override
		public InputStream getStream() {
			logger.info("导出用户列表 {} 条", viewUsers.size());
			return new ByteArrayInputStream(buildUserWorkbook(viewUsers));
		}
	}

	/**
	 * 把用户列表写成 xlsx 的字节数组。
	 * <p>
	 * 用 POI 直接写而不是 EasyExcel：EasyExcel 2.2.6 依赖 cglib，而 cglib 在 JDK 17
	 * 及以上会因模块访问限制抛 {@code InaccessibleObjectException}
	 * （反射调用 {@code ClassLoader.defineClass} 被拒），整条导出链路会直接报错。
	 * POI 没有这个依赖，JDK 8 / 17 / 21 上都能跑，样式也更可控。
	 * <p>
	 * 抽成 public static 是为了能脱离 Vaadin 环境单独验证。
	 */
	public static byte[] buildUserWorkbook(List<User> users) {
		String[] headers = { "用户名", "姓名", "所属组织", "邮箱", "手机号", "权限", "状态", "创建时间", "有效期至" };
		int[] widths = { 18, 12, 18, 26, 14, 22, 10, 20, 14 };
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (Workbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet("用户列表");
			CellStyle headStyle = buildHeadStyle(workbook);
			Row head = sheet.createRow(0);
			for (int i = 0; i < headers.length; i++) {
				Cell cell = head.createCell(i);
				cell.setCellValue(headers[i]);
				cell.setCellStyle(headStyle);
				sheet.setColumnWidth(i, widths[i] * 256);
			}
			int rowIndex = 1;
			for (User user : users) {
				Row row = sheet.createRow(rowIndex++);
				int col = 0;
				row.createCell(col++).setCellValue(StrUtil.nullToEmpty(user.getUserId()));
				row.createCell(col++).setCellValue(StrUtil.nullToEmpty(user.getUserName()));
				row.createCell(col++).setCellValue(StrUtil.nullToEmpty(user.getOrganization()));
				row.createCell(col++).setCellValue(StrUtil.nullToEmpty(user.getEmail()));
				row.createCell(col++).setCellValue(StrUtil.nullToEmpty(user.getCdPhone()));
				row.createCell(col++).setCellValue(user.permissionText());
				row.createCell(col++).setCellValue(user.statusText());
				row.createCell(col++).setCellValue(textCreateTime(user));
				row.createCell(col++).setCellValue(
						null == user.getExpireTime() ? "长期有效" : Util.formatDate(user.getExpireTime()));
			}
			// 冻结表头，往下翻时仍然知道每一列是什么
			sheet.createFreezePane(0, 1);
			workbook.write(out);
		} catch (Exception e) {
			logger.error("生成用户列表 xlsx 失败", e);
			return new byte[0];
		}
		return out.toByteArray();
	}

	private static CellStyle buildHeadStyle(Workbook workbook) {
		CellStyle style = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		style.setFont(font);
		style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		style.setBorderTop(BorderStyle.THIN);
		style.setBorderBottom(BorderStyle.THIN);
		style.setBorderLeft(BorderStyle.THIN);
		style.setBorderRight(BorderStyle.THIN);
		return style;
	}
}
