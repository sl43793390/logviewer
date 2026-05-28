package com.so.component.management;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.so.component.CommonComponent;
import com.so.component.util.ConfirmationDialogPopupWindow;
import com.so.component.util.ConfirmationEvent;
import com.so.component.util.ConfirmationEventListener;
import com.so.component.util.FileUploader;
import com.so.entity.CommonProjectMgmt;
import com.so.mapper.CommonProjectMgmtMapper;
import com.so.ui.ComponentFactory;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.so.util.Util;
import com.vaadin.ui.*;
import com.vaadin.ui.Notification.Type;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * 添加通用项目页面：
 * 
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class CommonProjecttMgmtLocal extends CommonComponent {

	
	private static final Logger log = LoggerFactory.getLogger(CommonProjecttMgmtLocal.class);
	private Panel mainPanel;
	private VerticalLayout contentLayout;

	public FileUploader loader;
	@Autowired
	private CommonProjectMgmtMapper commonProjectMapper;
	private TextField scriptPath;
	private TextField classField;
	private TextField cmdStart;
	private TextField idProjectField;
	private TextField descField;
	private Window win;
	private TextField nameProjectField;
	private Grid<CommonProjectMgmt> grid;
	private TextField cmdStop;
	private TextField cmdReStart;
	private TextField cmdRefresh;
	private TextField cmdStatus;

	@Override
	public void initLayout() {
		mainPanel = new Panel();
		setCompositionRoot(mainPanel);
		contentLayout = new VerticalLayout();
		contentLayout.setHeight("700px");
		contentLayout.setHeightFull();
		mainPanel.setContent(contentLayout);
		initMainLayout();
	}

	/**
	 * 布局
	 */
	private void initMainLayout() {
		QueryWrapper<CommonProjectMgmt> queryWrapper = new QueryWrapper<>();
		queryWrapper.eq("id_host","localhost");
		List<CommonProjectMgmt> selectList = commonProjectMapper.selectList(queryWrapper);
		grid = new Grid<CommonProjectMgmt>();
		grid.setWidthFull();
		grid.setHeightFull();
		grid.setItems(selectList);
		grid.addColumn(CommonProjectMgmt::getIdProject).setCaption("ID");
		grid.addColumn(CommonProjectMgmt::getCdTag).setCaption("tag");
		grid.addColumn(CommonProjectMgmt::getNameProject).setCaption("名称");
//		grid.addColumn(CommonProjectMgmt::getCdDescription).setCaption("描述");
		/** 使用componentColumn生成列的button ，相比render 更为灵活可以单独设置button的各种属性 */
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("启动");
			b.addClickListener(e -> {
				try {
					if (StrUtil.isNotBlank(p.getCdPath())) {
						log.info("脚本启动路径："+p.getCdPath());
						List<String> executeNewFlow = Util.executeNewFlow(Arrays.asList("cd " + p.getCdPath()+";"+p.getCmdStart()));
						log.info(executeNewFlow.toString());
						if (executeNewFlow.toString().contains("started")) {
							Notification.show("启动成功",Type.WARNING_MESSAGE);
						}else {
							Notification.show("启动失败",Type.ERROR_MESSAGE);
						}
					} 
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("启动失败，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
				}
				Notification.show("命令已经执行，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("启动服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("停止");
			b.addClickListener(e -> {
				try {
					if (StrUtil.isNotBlank(p.getCdPath())) {
						log.info("应用停止路径："+p.getCdPath());
						List<String> remoteExecute = Util.executeNewFlow(Arrays.asList("cd "+p.getCdPath()+";"+p.getCmdStop()));
						log.info(remoteExecute.toString());
					}
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("停止失败，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
				}
				Notification.show("停止命令已经执行，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("停止服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("重启");
			b.addClickListener(e -> {
				try {
					if (StrUtil.isNotBlank(p.getCdPath())) {
						log.info("应用重启路径："+p.getCdPath());
						List<String> remoteExecute = Util.executeNewFlow(Arrays.asList("cd "+p.getCdPath()+";"+p.getCmdRestart()));
						log.info(remoteExecute.toString());
					}
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("重启失败，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
				}
				Notification.show("停止命令已经执行，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("重启服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("刷新");
			b.addClickListener(e -> {
				try {
					if (StrUtil.isNotBlank(p.getCdPath())) {
						List<String> remoteExecute = Util.executeNewFlow(Arrays.asList("cd "+p.getCdPath()+";"+p.getCmdRefresh()));
						log.info(remoteExecute.toString());
					}
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("刷新失败，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
				}
				Notification.show("刷新命令已经执行，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("刷新配置");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("查看状态");
			b.addClickListener(e -> {
				try {
					// boolean runningStasus = Util.getRunningStasus("sh server.sh status " + p.getNameProject(), p.getCdParentPath());
					List<String> executeNewFlow = Util.executeNewFlow(Arrays.asList(p.getCmdStatus()));
					boolean falg = false;
					String binPath = StrUtil.removeSuffix(p.getCdPath(), "/");
					for (String res : executeNewFlow) {
						if (res.contains(p.getCmdStatus())) {
							b.setStyleName("projectlist-status-running-button");
							b.setCaption("运行中");
							Notification.show("服务运行中", Type.WARNING_MESSAGE);
							falg = true;
							break;
						} 
						log.info(res);
					}
					if (!falg) {
						b.setStyleName("projectlist-status-stop-button");
						b.setCaption("已停止");
						Notification.show("服务已经停止，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
					}
				} catch (Exception e1) {
					Notification.show("停止服务失败，请注意查看日志或点击状态按钮查看", Type.WARNING_MESSAGE);
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
				}
			});
			return b;
		}).setCaption("查看状态").setId("status");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("删除");
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
							UpdateWrapper<CommonProjectMgmt> wrap = new UpdateWrapper<>();
							wrap.eq("id_host","localhost").eq("id_project",p.getIdProject());
							commonProjectMapper.delete(wrap);
							QueryWrapper<CommonProjectMgmt> queryWrapper = new QueryWrapper<>();
							queryWrapper.eq("id_host","localhost");
							grid.setItems(commonProjectMapper.selectList(queryWrapper));
							yesNo.close();
						}

						@Override
						protected void rejected(ConfirmationEvent event) {
							super.rejected(event);
							return;
						}
					});
					yesNo.showConfirmation();
				} catch (Exception e1) {
					Notification.show("删除失败，请注意查看日志", Type.WARNING_MESSAGE);
					log.error(ExceptionUtils.getStackTrace(e1));
				}
			});
			return b;
		}).setCaption("删除");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("修改");
			b.addClickListener(e -> {
				try {
					if (!LoginView.checkPermission(Constants.DELETE)){
						Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
						return;
					}
					popWindowAddProject(false, p.getIdProject());
				} catch (Exception e1) {
					Notification.show("系统繁忙", Type.WARNING_MESSAGE);
					e1.printStackTrace();
				}
			});
			return b;
		}).setCaption("修改");
		grid.addComponentColumn(p -> {
			loader = new FileUploader();
			String tomPath = StrUtil.removeSuffix(p.getCdPath(), "/");
			loader.setParentPath(tomPath+File.separator+"webapps");
			loader.setIdProject(p.getIdProject());
			Upload upload = new Upload("上传", loader);
			upload.setImmediateMode(true);
			upload.setButtonCaption("上传");
			upload.addStyleName("upload-style-button");
			upload.setHeight("30px");
			upload.addSucceededListener(loader);
			return upload;
		}).setCaption("上传文件");
		Button btn = ComponentFactory.getStandardButton("添加项目");
		btn.addClickListener(e -> popWindowAddProject(true, null));// false代表修改
		contentLayout.addComponent(btn);
		contentLayout.addComponent(grid);
		contentLayout.setComponentAlignment(btn, Alignment.MIDDLE_RIGHT);
		contentLayout.setExpandRatio(btn, 1);
		contentLayout.setExpandRatio(grid, 10);

	}

	private void saveOrUpdateProject(boolean update) {

		CommonProjectMgmt pro = new CommonProjectMgmt();
		if (idProjectField.getValue() == null || scriptPath.getValue() == null) {
			Notification.show("项目ID、项目所在路径不能为空！", Type.WARNING_MESSAGE);
			return;
		}
		String id = StringUtils.removeEnd(idProjectField.getValue(), "/");
		pro.setIdProject(id);
		pro.setIdHost("localhost");
		pro.setNameProject(nameProjectField.getValue());
		pro.setCdPath(scriptPath.getValue());
		pro.setCdTag(classField.getValue());
		pro.setCmdStart(cmdStart.getValue());
		pro.setCmdStop(cmdStop.getValue());
		pro.setCmdRestart(cmdReStart.getValue());
		pro.setCmdRefresh(cmdRefresh.getValue());
		pro.setCmdStatus(cmdStatus.getValue());
		pro.setCdDescription(descField.getValue());
		if (update) {
			QueryWrapper<CommonProjectMgmt> wrap = new QueryWrapper<CommonProjectMgmt>();
			wrap.eq("id_host", "localhost").eq("id_project",pro.getIdProject());
			CommonProjectMgmt p = commonProjectMapper.selectOne(wrap);
			if (null != p) {
				Notification.show("项目ID不能重复！", Type.WARNING_MESSAGE);
				return;
			}else {
				commonProjectMapper.insert(pro);
			}
		} else {
			UpdateWrapper<CommonProjectMgmt> wrap = new UpdateWrapper<>();
			wrap.eq("id_host", "localhost").eq("id_project",pro.getIdProject());
			commonProjectMapper.update(pro,wrap);
		}
		QueryWrapper<CommonProjectMgmt> wrap2 = new QueryWrapper<CommonProjectMgmt>();
		wrap2.eq("id_host", "localhost");
		grid.setItems(commonProjectMapper.selectList(wrap2));
		win.close();
		Notification.show("保存成功", Type.WARNING_MESSAGE);
		return;
	}

	private void popWindowAddProject(boolean update, String idProject) {
		FormLayout lay = new FormLayout();
		lay.addStyleName("project-addproject-window");
		idProjectField = ComponentFactory.getStandardTtextField("项目ID");
		idProjectField.setWidth("370px");
		nameProjectField = ComponentFactory.getStandardTtextField("项目名称");
		nameProjectField.setWidth("370px");
		nameProjectField.setPlaceholder("不可以为空");
		scriptPath = ComponentFactory.getStandardTtextField("脚本存放目录");
		scriptPath.setDescription("确保该目录脚本有执行权限");
		scriptPath.setWidth("370px");
		scriptPath.setPlaceholder("注：脚本存放目录");
		classField = ComponentFactory.getStandardTtextField("tag");
		classField.setWidth("370px");
		cmdStart = ComponentFactory.getStandardTtextField("启动命令");
		cmdStart.setPlaceholder("示例：sh server.sh start");
		cmdStart.setWidth("370px");
		cmdStop = ComponentFactory.getStandardTtextField("停止命令");
		cmdStop.setPlaceholder("示例：sh server.sh stop");
		cmdStop.setWidth("370px");
		cmdReStart = ComponentFactory.getStandardTtextField("重启命令");
		cmdReStart.setPlaceholder("示例：sh server.sh restart");
		cmdReStart.setWidth("370px");
		cmdRefresh = ComponentFactory.getStandardTtextField("刷新配置命令");
		cmdRefresh.setPlaceholder("示例：sh server.sh refresh");
		cmdRefresh.setWidth("370px");
		cmdStatus = ComponentFactory.getStandardTtextField("查看状态");
		cmdStatus.setPlaceholder("示例：sh server.sh status");
		cmdStatus.setWidth("370px");
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
		lay.addComponent(scriptPath);
		lay.addComponent(classField);
		lay.addComponent(cmdStart);
		lay.addComponent(cmdStop);
		lay.addComponent(cmdReStart);
		lay.addComponent(cmdRefresh);
		lay.addComponent(cmdStatus);
		lay.addComponent(descField);
		lay.addComponent(saveBtn);

		if (!update) {
			QueryWrapper<CommonProjectMgmt> wrap = new QueryWrapper<CommonProjectMgmt>();
			wrap.eq("id_host", "localhost").eq("id_project",idProject);
			CommonProjectMgmt p = commonProjectMapper.selectOne(wrap);
			idProjectField.setValue(p.getIdProject());
			nameProjectField.setValue(p.getNameProject() == null ? "" : p.getNameProject());
			idProjectField.setEnabled(false);
			scriptPath.setValue(p.getCdPath() == null ? "" : p.getCdPath());
			cmdStart.setValue(p.getCmdStart() == null ? "" : p.getCmdStart());
			cmdStop.setValue(p.getCmdStop() == null ? "" : p.getCmdStop());
			cmdReStart.setValue(p.getCmdRestart() == null ? "" : p.getCmdRestart());
			cmdRefresh.setValue(p.getCmdRefresh() == null ? "" : p.getCmdRefresh());
			cmdStatus.setValue(p.getCmdStatus() == null ? "" : p.getCmdStatus());
			descField.setValue(p.getCdDescription() == null ? "" : p.getCdDescription());
			classField.setValue(p.getCdTag() == null ? "" : p.getCdTag());
		}

		win = new Window("添加项目");
		win.setHeight("605px");
		win.setWidth("600px");
		win.setModal(true);

		win.setContent(lay);
		UI.getCurrent().addWindow(win);
	}

	@Override
	public void initContent() {
		// TODO Auto-generated method stub

	}

	@Override
	public void registerHandler() {
		// TODO Auto-generated method stub

	}

}
