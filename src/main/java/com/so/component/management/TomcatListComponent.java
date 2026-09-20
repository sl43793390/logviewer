package com.so.component.management;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.so.component.util.*;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.ui.*;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.so.ui.ComponentFactory;
import com.so.util.Util;
import com.so.component.CommonComponent;
import com.so.entity.ProjectList;
import com.so.entity.TomcatInfoEntity;
import com.so.mapper.ProjectsMapper;
import com.so.mapper.TomcatInfoMapper;

import cn.hutool.core.util.StrUtil;

/**
 * 添加项目页面：
 * 支持上传文件到项目根目录，启动jar包，停止jar包，查看日志目录并下载
 * 
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class TomcatListComponent extends CommonComponent {


	private static final Logger log = LoggerFactory.getLogger(TomcatListComponent.class);
	private static final long serialVersionUID = -5516121570034623010L;
	public static final String LOCALHOST = "localhost";
	private Panel mainPanel;
	private VerticalLayout contentLayout;

	public FileUploader loader;
	@Autowired
	private TomcatInfoMapper tomcatInfoMapper;
	private TextField tomcatPath;
	private TextField classField;
	private TextField webappField;
	private TextField idProjectField;
	private TextField descField;
	private Window win;
	private TextField nameProjectField;
	private Grid<TomcatInfoEntity> grid;
	private Button searchBtn;
	private TextField nameField;
	private TextField tagfield;

	@Override
	public void initLayout() {
		mainPanel = new Panel();
		setCompositionRoot(mainPanel);
		contentLayout = new VerticalLayout();
		contentLayout.setWidth("100%");
		contentLayout.setHeight("700px");
		mainPanel.setContent(contentLayout);
		initMainLayout();
	}

	/**
	 * 布局
	 */
	private void initMainLayout() {
		AbsoluteLayout absoluteLayout = ComponentFactory.getAbsoluteLayout();
		Label standardLabel = ComponentFactory.getStandardLabel("名称:");
		nameField = ComponentFactory.getStandardTtextField();
		Label tag = ComponentFactory.getStandardLabel("标签:");
		tagfield = ComponentFactory.getStandardTtextField();
		absoluteLayout.addComponent(standardLabel);
		absoluteLayout.addComponent(nameField,"left:50px");
		absoluteLayout.addComponent(tag,"left:280px");
		absoluteLayout.addComponent(tagfield,"left:335px");
		searchBtn = ComponentFactory.getStandardButton("搜索");
		Button btn = ComponentFactory.getStandardButton("添加项目");
		btn.addClickListener(e -> {
			if (!LoginView.checkPermission(Constants.ADD)){
				Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
				return;
			}
			popWindowAddProject(true, null);
		});// false代表修改
		absoluteLayout.addComponent(searchBtn,"left:590px");
		absoluteLayout.addComponent(btn,"left:690px");
		contentLayout.addComponent(absoluteLayout);

		QueryWrapper<TomcatInfoEntity> wrapper = new QueryWrapper<>();
		wrapper.eq("id_host", LOCALHOST);
		List<TomcatInfoEntity> selectList = tomcatInfoMapper.selectList(wrapper);
		grid = new Grid<TomcatInfoEntity>();
		contentLayout.addComponent(grid);
		contentLayout.setExpandRatio(absoluteLayout, 1);
		contentLayout.setExpandRatio(grid, 10);
		grid.setWidthFull();
		grid.setHeightFull();
		grid.setItems(selectList);
		grid.addColumn(TomcatInfoEntity::getTomcatId).setCaption("ID");
		grid.addColumn(TomcatInfoEntity::getTag).setCaption("tag");
		grid.addColumn(TomcatInfoEntity::getNameTomcat).setCaption("名称");
		grid.addColumn(TomcatInfoEntity::getCdDescription).setCaption("描述");
		/** 使用componentColumn生成列的button ，相比render 更为灵活可以单独设置button的各种属性 */
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("启动");
			b.addClickListener(e -> {
				try {
					if (StrUtil.isNotBlank(p.getTomcatPath())) {
						log.info("tomcat启动路径："+p.getTomcatPath());
						List<String> executeNewFlow = Util.executeNewFlow(Arrays.asList("cd "+p.getTomcatPath(),"./bin/startup.sh"));
						log.info(executeNewFlow.toString());
					} 
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("启动失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
				}
				Notification.show("命令已经执行，请注意查看日志", Notification.Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("启动服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("停止");
			b.addClickListener(e -> {
				try {
					if (StrUtil.isNotBlank(p.getTomcatPath())) {
						log.info("tomcat停止路径："+p.getTomcatPath());
						Util.executeNewFlow(Arrays.asList("cd "+p.getTomcatPath(),"./bin/shutdown.sh"));
					} 
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("停止失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
				}
				Notification.show("停止命令已经执行，请注意查看日志", Notification.Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("停止服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("查看状态");
			b.addClickListener(e -> {
				try {
					// boolean runningStasus = Util.getRunningStasus("sh server.sh status " + p.getNameProject(), p.getCdParentPath());
					List<String> executeNewFlow = Util.executeNewFlow(Arrays.asList("ps -ef | grep java"));
					boolean falg = false;
					String binPath = StrUtil.removeSuffix(p.getTomcatPath(), "/");
					for (String res : executeNewFlow) {
						if (res.contains(binPath)) {
							b.setStyleName("projectlist-status-running-button");
							b.setCaption("运行中");
							Notification.show("服务运行中", Notification.Type.WARNING_MESSAGE);
							falg = true;
							break;
						} 
						log.info(res);
					}
					if (!falg) {
						b.setStyleName("projectlist-status-stop-button");
						b.setCaption("已停止");
						Notification.show("服务已经停止，请注意查看日志", Notification.Type.WARNING_MESSAGE);
					}
				} catch (Exception e1) {
					Notification.show("停止服务失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
				}
			});
			return b;
		}).setCaption("查看状态");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getButtonWithColor("删除", ColorEnum.RED);
			b.addClickListener(e -> {
				try {
					if (!LoginView.checkPermission(Constants.DELETE)){
						Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
						return;
					}
					ConfirmationDialogPopupWindow yesNo = new ConfirmationDialogPopupWindow("确认", "请确认是否要删除！", "确定", "放弃", false);
					yesNo.addListener(new ConfirmationEventListener() {
						private static final long serialVersionUID = -8751718063979484449L;
						@Override
						protected void confirmed(ConfirmationEvent event) {
							UpdateWrapper<TomcatInfoEntity> wrapper = new UpdateWrapper<>();
							wrapper.eq("id_host", LOCALHOST).eq("tomcat_id",p.getTomcatId());
							tomcatInfoMapper.delete(wrapper);
							QueryWrapper<TomcatInfoEntity> wrapper2 = new QueryWrapper<>();
							wrapper2.eq("id_host", LOCALHOST);
							grid.setItems(tomcatInfoMapper.selectList(wrapper2));
						}

						@Override
						protected void rejected(ConfirmationEvent event) {
							super.rejected(event);
							return;
						}
					});
					yesNo.showConfirmation();
				} catch (Exception e1) {
					Notification.show("删除失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
				}
			});
			return b;
		}).setCaption("删除");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("修改");
			b.addClickListener(e -> {
				try {
					popWindowAddProject(false, p.getTomcatId());
				} catch (Exception e1) {
					Notification.show("系统繁忙", Notification.Type.WARNING_MESSAGE);
					e1.printStackTrace();
				}
			});
			return b;
		}).setCaption("修改");
		grid.addComponentColumn(p -> {
			loader = new FileUploader();
			String tomPath = StrUtil.removeSuffix(p.getTomcatPath(), "/");
			loader.setParentPath(tomPath+File.separator+"webapps");
			loader.setIdProject(p.getTomcatId());
			Upload upload = new Upload("上传", loader);
			upload.setImmediateMode(true);
			upload.setButtonCaption("上传");
			upload.addStyleName("upload-style-button");
			upload.setHeight("30px");
			upload.addSucceededListener(loader);
			return upload;
		}).setCaption("上传war包");
	}

	private void saveOrUpdateProject(boolean update) {

		TomcatInfoEntity pro = new TomcatInfoEntity();
		if (StrUtil.isBlank(idProjectField.getValue()) || StrUtil.isBlank(tomcatPath.getValue())) {
			Notification.show("项目ID、项目所在路径不能为空！", Notification.Type.WARNING_MESSAGE);
			return;
		}
		pro.setIdHost(LOCALHOST);
		pro.setTomcatId(idProjectField.getValue().trim());
		pro.setNameTomcat(nameProjectField.getValue());
		pro.setTomcatPath(tomcatPath.getValue().trim());
		pro.setTag(classField.getValue());
		pro.setWebappPath(webappField.getValue());
		pro.setCdDescription(descField.getValue());
		if (update) {
			QueryWrapper<TomcatInfoEntity> wrapper2 = new QueryWrapper<>();
			wrapper2.eq("id_host", LOCALHOST).eq("tomcat_id",pro.getTomcatId());
			TomcatInfoEntity selectById = tomcatInfoMapper.selectOne(wrapper2);
			if (null != selectById) {
				Notification.show("项目ID不能重复！", Notification.Type.WARNING_MESSAGE);
				return;
			}else {
				tomcatInfoMapper.insert(pro);
			}
		} else {
			UpdateWrapper<TomcatInfoEntity> updateWrapper = new UpdateWrapper<>();
			updateWrapper.eq("id_host",pro.getIdHost()).eq("tomcat_id",pro.getTomcatId());
			tomcatInfoMapper.delete(updateWrapper);
			tomcatInfoMapper.insert(pro);
		}
		QueryWrapper<TomcatInfoEntity> wrapper = new QueryWrapper<>();
		wrapper.eq("id_host", LOCALHOST);
		grid.setItems(tomcatInfoMapper.selectList(wrapper));
		win.close();
		Notification.show("保存成功", Notification.Type.WARNING_MESSAGE);
		return;
	}

	private void popWindowAddProject(boolean update, String idProject) {
		FormLayout lay = new FormLayout();
		lay.addStyleName("project-addproject-window");
		idProjectField = ComponentFactory.getStandardTtextField("项目ID");
		idProjectField.setRequiredIndicatorVisible(true);
		idProjectField.setWidth("370px");
		nameProjectField = ComponentFactory.getStandardTtextField("Tomcat名称");
		nameProjectField.setRequiredIndicatorVisible(true);
		nameProjectField.setWidth("370px");
		nameProjectField.setPlaceholder("可以为空");
		tomcatPath = ComponentFactory.getStandardTtextField("Tomcat的主目录");
		tomcatPath.setRequiredIndicatorVisible(true);
		tomcatPath.setDescription("确保该目录下包含bin、webapps、conf、lib等目录");
		tomcatPath.setWidth("370px");
		tomcatPath.setPlaceholder("注：Tomcat主目录");
		classField = ComponentFactory.getStandardTtextField("tag");
		classField.setWidth("370px");
		webappField = ComponentFactory.getStandardTtextField("Tomcat-webapps目录");
		webappField.setPlaceholder("可以为空");
		webappField.setWidth("370px");
		descField = ComponentFactory.getStandardTtextField("项目描述");
		descField.setWidth("370px");
		Button saveBtn = ComponentFactory.getStandardButton("保存");
		saveBtn.addClickListener(e -> {
			if (update) {
				saveOrUpdateProject(update);
			} else {
				saveOrUpdateProject(update);// 修改
			}
		});
		lay.addComponent(idProjectField);
		lay.addComponent(nameProjectField);
		lay.addComponent(tomcatPath);
		lay.addComponent(classField);
		lay.addComponent(webappField);
		lay.addComponent(descField);
		lay.addComponent(saveBtn);

		if (!update) {
			QueryWrapper<TomcatInfoEntity> wrapper = new QueryWrapper<>();
			wrapper.eq("id_host", LOCALHOST).eq("tomcat_id",idProject);
			TomcatInfoEntity p = tomcatInfoMapper.selectOne(wrapper);
			if (null != p){
				idProjectField.setValue(p.getTomcatId());
				nameProjectField.setValue(p.getNameTomcat() == null ? "" : p.getNameTomcat());
				idProjectField.setEnabled(false);
				tomcatPath.setValue(p.getTomcatPath() == null ? "" : p.getTomcatPath());
				webappField.setValue(p.getWebappPath() == null ? "" : p.getWebappPath());
				descField.setValue(p.getCdDescription() == null ? "" : p.getCdDescription());
				classField.setValue(p.getTag() == null ? "" : p.getTag());
			}
		}

		win = new Window("添加项目");
		win.setHeight("500px");
		win.setWidth("600px");
		win.setModal(true);

		win.setContent(lay);
		UI.getCurrent().addWindow(win);
	}

	@Override
	public void initContent() {

	}

	@Override
	public void registerHandler() {
		searchBtn.addClickListener(e ->{
			String name = nameField.getValue();
			String tag = tagfield.getValue();
			if (StrUtil.isEmpty(name) && StrUtil.isEmpty(tag)) {
				QueryWrapper<TomcatInfoEntity> query = new QueryWrapper<TomcatInfoEntity>();
				query.eq("id_host", LOCALHOST);
				List<TomcatInfoEntity> selectByMap = tomcatInfoMapper.selectList(query);
				grid.setItems(selectByMap);
				return;
			}
			QueryWrapper<TomcatInfoEntity> query = new QueryWrapper<TomcatInfoEntity>();
			query.eq("id_host", LOCALHOST);
			if (StrUtil.isNotEmpty(name)) {
				query.like("name_tomcat", name);
			}
			if (StrUtil.isNotEmpty(tag)) {
				query.like("tag", tag);
			}
			List<TomcatInfoEntity> selectByMap = tomcatInfoMapper.selectList(query);
			grid.setItems(selectByMap);
		});
	}


}
