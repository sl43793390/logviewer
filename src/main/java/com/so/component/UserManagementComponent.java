package com.so.component;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.entity.User;
import com.so.mapper.UserDao;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.so.util.Util;
import com.vaadin.ui.*;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.ui.Grid.SelectionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.vaadin.addons.ComboBoxMultiselect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Scope("prototype")
public class UserManagementComponent extends CommonComponent {

	private static final long serialVersionUID = 5187805936625809077L;
	private static final Logger logger = LoggerFactory.getLogger(UserManagementComponent.class);
	private Panel mainPanel;
	private VerticalLayout workingAreaLayout;

	private Grid<User> userGrid;
	private Button addBtn;
	private Button removeBtn;
	private Button updateBtn;
	private Button searchButton;
	@Autowired
	private UserDao userDao;

	// private Label pagelb;
	private TextField searchField;
	private List<User> users;

	@Override
	public void initLayout() {
		mainPanel = ComponentFactory.getSubPanel();
		setCompositionRoot(mainPanel);
		workingAreaLayout = ComponentFactory.getMainLayout();
		// workingAreaLayout.setHeight("800px");
		mainPanel.setContent(workingAreaLayout);
		initUserListLayout();
	}

	private void initUserListLayout() {
		// 此处分为三部分layout；1、用户列表标题及搜索框；2、用户列表table；3、操作按钮区；
		initTitleAndSearchLayout();
		initUserListTableLayout();
		initButton();

	}

	private void initTitleAndSearchLayout() {

		AbsoluteLayout la = ComponentFactory.getTitleLayout("用户列表");
		workingAreaLayout.addComponent(la);

		Label label1 = ComponentFactory.getStandardLabel("用户名：");
		searchField = ComponentFactory.getStandardTtextField();
		searchButton = ComponentFactory.getStandardButton("搜索");
//		searchButton.setIcon(new ThemeResource("img/search.png"));
		AbsoluteLayout out1 = ComponentFactory.getAbsoluteLayout();
		out1.setHeight("60px");
		out1.addComponent(label1, "left:0px;top:18px");
		out1.addComponent(searchField, "left:" + ComponentFactory.getPosition(0, 8) + "px;top:15px");
		out1.addComponent(searchButton, "left:260px;top:15px");
		workingAreaLayout.addComponent(out1);
	}

	private void initUserListTableLayout() {
		userGrid = new Grid<User>();
		userGrid.setSelectionMode(SelectionMode.SINGLE);
		userGrid.addColumn(User::getUserId).setCaption("用户名");
		workingAreaLayout.addComponent(userGrid);
	}

	private void initButton() {
		addBtn = ComponentFactory.getStandardButton("新增");
		removeBtn = ComponentFactory.getStandardButton("删除");
		updateBtn = ComponentFactory.getStandardButton("修改");
		AbsoluteLayout absoluteLayout = new AbsoluteLayout();
		absoluteLayout.setHeight("80px");
		absoluteLayout.setWidth("100%");
		absoluteLayout.addComponent(addBtn, "left:20px;top:15px");
		absoluteLayout.addComponent(updateBtn, "left:115px;top:15px");
		absoluteLayout.addComponent(removeBtn, "left:205px;top:15px");
		workingAreaLayout.addComponent(absoluteLayout);
		workingAreaLayout.setExpandRatio(absoluteLayout, 1);

	}

	@Override
	public void initContent() {
		initContentTable();
	}

	public void initContentTable() {
		users = userDao.selectList(new QueryWrapper<User>());
		populateUserGrid(users);
	}

	private void populateUserGrid(List<User> users) {
		// Create a grid bound to the list
		userGrid.markAsDirty();
		userGrid.setItems(users);
	}

	@Override
	public void registerHandler() {

		searchButton.addClickListener(new ClickListener() {
			private static final long serialVersionUID = 1L;

			@Override
			public void buttonClick(ClickEvent event) {
				String value = searchField.getValue();
				List<User> us = new ArrayList<User>();
				users.forEach(e ->{
					if (e.getUserId().contains(value)) {
						us.add(e);
					}
				});
				populateUserGrid(us);
			}
		});
		addBtn.addClickListener(new ClickListener() {

			@Override
			public void buttonClick(ClickEvent event) {
				if (!LoginView.checkPermission(Constants.ADD)){
					Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
					return;
				}
				MyWindow win = new MyWindow("添加用户",true);
				UI.getCurrent().addWindow(win);
			}
		});

		removeBtn.addClickListener(new ClickListener() {
			private static final long serialVersionUID = -2619213342809375236L;

			@Override
			public void buttonClick(ClickEvent event) {
				if (!LoginView.checkPermission(Constants.DELETE)){
					Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
					return;
				}
				Set<User> selectedItems = userGrid.getSelectedItems();
				if (null == selectedItems || selectedItems.isEmpty()) {
					Notification.show("未选择用户", Notification.Type.WARNING_MESSAGE);
					return;
				}
				Set<String> idsToRemove = new HashSet<String>();
				for (User user : selectedItems) {
					idsToRemove.add(user.getUserId());
				}
				// 原实现在 for-each 里直接 allUser.remove(id)，必然抛
				// ConcurrentModificationException；这里改成重建一个新列表
				List<String> allUser = Util.getUsersAsLine();
				List<String> remainUsers = new ArrayList<String>();
				boolean flag = false;
				for (String line : allUser) {
					int index = line.indexOf('=');
					if (index > 0 && idsToRemove.contains(line.substring(0, index).trim())) {
						flag = true;
						continue;
					}
					remainUsers.add(line);
				}
				if (flag) {
					Util.saveUsers(remainUsers);
					Notification.show("删除成功", Notification.Type.WARNING_MESSAGE);
				} else {
					Notification.show("该用户在配置文件中不存在，无需删除", Notification.Type.WARNING_MESSAGE);
				}
				initContentTable();
			}

		});

		updateBtn.addClickListener(new ClickListener() {

			private static final long serialVersionUID = -437607647890262278L;

			@Override
			public void buttonClick(ClickEvent event) {
				if (!LoginView.checkPermission(Constants.UPDATE)){
					Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
					return;
				}
				Set<User> selectedItems = userGrid.getSelectedItems();
				if (null == selectedItems || selectedItems.isEmpty()) {
					Notification.show("未选择用户", Notification.Type.WARNING_MESSAGE);
					return;
				}
				MyWindow win = new MyWindow("修改用户",false);
				UI.getCurrent().addWindow(win);
			}
		});
	}

	/**
	 * 新增参数为true,修改为false
	 * @author Administrator
	 *
	 */
	class MyWindow extends Window {
		private static final long serialVersionUID = 3394269109511557536L;

		public MyWindow(String title,boolean add) {
	        super(title); // Set window caption
	        center();
			setModal(true);
	        setClosable(true);
	        setHeight("370px");
			setWidth("370px");
			AbsoluteLayout abs = ComponentFactory.getAbsoluteLayout();
			abs.setHeightFull();
			FormLayout lay = new FormLayout();
			lay.setHeight("200px");
			TextField usernameField = ComponentFactory.getStandardTtextField("用户名：");
			TextField passField = ComponentFactory.getStandardPassedwordField("密码：");
			ComboBoxMultiselect permission = ComponentFactory.getComboxMultiselect("权限");
			permission.setItems(Constants.ADD,Constants.DELETE,Constants.UPDATE,Constants.QUERY,Constants.ALL);
			if (!add) {
				User currentSelectedUser = null;
				Set<User> selectedItems = userGrid.getSelectedItems();
				for (User user : selectedItems) {
					currentSelectedUser = user;
					usernameField.setValue(user.getUserId());
					usernameField.setEnabled(false);
					break;
				}
				User user = userDao.selectById(currentSelectedUser.getUserId());
				if (StrUtil.isNotEmpty(user.getPermission())){
					String[] split = user.getPermission().split(",");
					for (int i = 0; i < split.length; i++) {
						permission.select(split[i]);
					}
				}
			}else{
				permission.select(Constants.QUERY);
			}
			lay.addComponent(usernameField);
			lay.addComponent(passField);
			lay.addComponent(permission);
			abs.addComponent(lay,"left:10px;top:5px;");
			AbsoluteLayout btnLayout = ComponentFactory.getAbsoluteLayout();
			abs.addComponent(btnLayout,"bottom:50px;right:20px;");
			Button confirmBtn = ComponentFactory.getStandardButton("确定");
			confirmBtn.addClickListener(e ->{
				if (!add) {
					User user = userDao.selectById(usernameField.getValue());
					if (StrUtil.isNotEmpty(passField.getValue())){
						user.setPassword(Util.getSm3DigestStr(passField.getValue()));
					}
					Set<Object> value = permission.getValue();
					if (null != value){
						StringBuffer buf = new StringBuffer();
						value.stream().forEach(e1 -> buf.append(e1.toString()+","));
						user.setPermission(buf.toString());
					}else{
						user.setPermission(Constants.QUERY);
					}
					userDao.updateById(user);
					this.close();
				}else {
					if (null != usernameField.getValue() && !"".equals(usernameField.getValue()) &&
							null != passField.getValue() && !"".equals(passField.getValue())) {
						User user = userDao.selectById(usernameField.getValue());
						if (null != user) {
							Notification.show("用户已经存在,请更换用户名尝试!", Notification.Type.WARNING_MESSAGE);
							return;
						}
						User u = new User();
						u.setUserId(usernameField.getValue());
						u.setPassword(Util.getSm3DigestStr(passField.getValue()));
						Set<Object> value = permission.getValue();
						if (null != value) {
							StringBuffer buf = new StringBuffer();
							value.stream().forEach(e1 -> buf.append(e1.toString() + ","));
							u.setPermission(buf.toString());
						} else {
							u.setPermission(Constants.QUERY);
						}
						userDao.insert(u);
						this.close();
					} else {
						Notification.show("用户名或密码输入有误", Notification.Type.WARNING_MESSAGE);
						return;
					}
				}
				initContentTable();
			});
			Button cancel = ComponentFactory.getStandardButton("取消");
			cancel.addClickListener(e ->{
				this.close();
			});
			btnLayout.addComponent(confirmBtn,"right:100px;");
			btnLayout.addComponent(cancel,"right:10px;");
	        setContent(abs);
	    }
	}

}
