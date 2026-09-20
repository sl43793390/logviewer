package com.so.component.remote;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jcraft.jsch.Session;
import com.so.ui.ComponentFactory;
import com.so.util.MyJSchUtil;
import com.so.component.CommonComponent;
import com.so.component.util.ConfirmationDialogPopupWindow;
import com.so.component.util.ConfirmationEvent;
import com.so.component.util.ConfirmationEventListener;
import com.so.component.util.RemoteFileUploader;
import com.so.entity.ConnectionInfo;
import com.so.entity.CommonProjectMgmt;
import com.so.mapper.CommonProjectMgmtMapper;

import cn.hutool.core.util.StrUtil;

/**
 * 添加通用项目页面：
 * 
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class CommonProjecttMgmtComponent extends CommonComponent {

	
	private static final Logger log = LoggerFactory.getLogger(CommonProjecttMgmtComponent.class);
	private static final long serialVersionUID = -5516121570034623010L;
	private Panel mainPanel;
	private VerticalLayout contentLayout;

	public RemoteFileUploader loader;
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
	private ConnectionInfo addr;
	private Session jschSession;
	private TextField cmdStop;
	private TextField cmdReStart;
	private TextField cmdRefresh;
	private TextField cmdStatus;
	private TextField cmdStatusKey;

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
		QueryWrapper<CommonProjectMgmt> queryWrapper = new QueryWrapper<>();
		queryWrapper.eq("id_host",addr.getIdHost());
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
						List<String> executeNewFlow = MyJSchUtil.remoteExecute(jschSession,"source /etc/profile;cd " + p.getCdPath()+";"+p.getCmdStart());
						log.info(executeNewFlow.toString());
					}
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("启动失败，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
				}
				Notification.show("命令已经执行，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("启动服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("停止");
			b.addClickListener(e -> {
				try {
					if (StrUtil.isNotBlank(p.getCdPath())) {
						log.info("应用停止路径："+p.getCdPath());
						List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession,"source /etc/profile;cd "+p.getCdPath()+";"+p.getCmdStop());
						log.info(remoteExecute.toString());
					}
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("停止失败，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
				}
				Notification.show("停止命令已经执行，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("停止服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("重启");
			b.addClickListener(e -> {
				try {
					// 原来判断的是 cdPath，却提示"未配置重启命令"，字段判断和提示对不上
					if (StrUtil.isBlank(p.getCmdRestart())) {
						Notification.show("未配置重启命令，无法执行！", Notification.Type.WARNING_MESSAGE);
						return;
					}
					log.info("应用重启路径："+p.getCdPath());
					List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession,"source /etc/profile;cd "+p.getCdPath()+";"+p.getCmdRestart());
					log.info(remoteExecute.toString());
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("重启失败，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
				}
				Notification.show("重启命令已经执行，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("重启服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("刷新");
			b.addClickListener(e -> {
				try {
					if (StrUtil.isBlank(p.getCmdRefresh())) {
						Notification.show("未配置刷新命令，无法执行！", Notification.Type.WARNING_MESSAGE);
						return;
					}
					List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession,"source /etc/profile;cd "+p.getCdPath()+";"+p.getCmdRefresh());
					log.info(remoteExecute.toString());
				} catch (Exception e1) {
					e1.printStackTrace();
					log.error(ExceptionUtils.getStackTrace(e1));
					Notification.show("刷新失败，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
				}
				Notification.show("刷新命令已经执行，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("刷新配置");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("查看状态");
			b.addClickListener(e -> {
				try {
					// boolean runningStasus = Util.getRunningStasus("sh server.sh status " + p.getNameProject(), p.getCdParentPath());
					if (StrUtil.isNotEmpty(p.getCmdStatus())){
						List<String> executeNewFlow = MyJSchUtil.remoteExecute(jschSession,p.getCmdStatus());
						boolean falg = false;
						// 原来直接用 getCmdStatusSuccessKey() 参与 contains：
						// key 未配置时 contains(null) 抛 NPE，key 为空串时 contains("") 恒为 true（永远显示"运行中"）
						String successKey = p.getCmdStatusSuccessKey();
						if (StrUtil.isEmpty(successKey)) {
							Notification.show("未配置状态检查关键字，无法判断运行状态", Notification.Type.WARNING_MESSAGE);
							return;
						}
						for (String res : executeNewFlow) {
							if (res.contains(successKey)) {
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
							Notification.show("服务已经停止，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
						}
					}else{
						Notification.show("未配置查看状态命令，无法执行！", Notification.Type.WARNING_MESSAGE);
					}

				} catch (Exception e1) {
					Notification.show("执行命令失败，请注意查看日志或点击状态按钮查看", Notification.Type.WARNING_MESSAGE);
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
							wrap.eq("id_host",addr.getIdHost()).eq("id_project",p.getIdProject());
							commonProjectMapper.delete(wrap);
							QueryWrapper<CommonProjectMgmt> queryWrapper = new QueryWrapper<>();
							queryWrapper.eq("id_host",addr.getIdHost());
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
					Notification.show("删除失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
					log.error(ExceptionUtils.getStackTrace(e1));
				}
			});
			return b;
		}).setCaption("删除");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("修改");
			b.addClickListener(e -> {
				try {
					if (!LoginView.checkPermission(Constants.UPDATE)){
						Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
						return;
					}
					popWindowAddProject(false, p.getIdProject());
				} catch (Exception e1) {
					Notification.show("系统繁忙", Notification.Type.WARNING_MESSAGE);
					e1.printStackTrace();
				}
			});
			return b;
		}).setCaption("修改");
		grid.addComponentColumn(p -> {
			// 远程项目必须走 RemoteFileUploader。原来用本地 FileUploader +
			// 远端绝对路径当本地路径，FileOutputStream 直接抛 FileNotFoundException，
			// 上传静默失败（receiveUpload 返回 null）
			loader = new RemoteFileUploader();
			loader.setRemoteFlag(true);
			loader.setSession(jschSession);
			loader.setAddr(addr);
			loader.setParentPath(StrUtil.removeSuffix(p.getCdPath(), "/"));
			loader.setIdProject(p.getIdProject());
			Upload upload = new Upload("上传", loader);
			upload.setImmediateMode(true);
			upload.addStartedListener(event -> {
				if (!LoginView.checkPermission(Constants.UPLOAD)) {
					Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
					throw new RuntimeException("权限不足，终止上传");
				}
			});
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
		if (StrUtil.isBlank(idProjectField.getValue()) || StrUtil.isBlank(scriptPath.getValue())) {
			Notification.show("项目ID、项目所在路径不能为空！", Notification.Type.WARNING_MESSAGE);
			return;
		}
		String id = StringUtils.removeEnd(idProjectField.getValue().trim(), "/");
		pro.setIdProject(id);
		pro.setIdHost(addr.getIdHost());
		pro.setNameProject(nameProjectField.getValue());
		pro.setCdPath(scriptPath.getValue());
		pro.setCdTag(classField.getValue());
		pro.setCmdStart(cmdStart.getValue());
		pro.setCmdStop(cmdStop.getValue());
		pro.setCmdRestart(cmdReStart.getValue());
		pro.setCmdRefresh(cmdRefresh.getValue());
		pro.setCmdStatus(cmdStatus.getValue());
		pro.setCmdStatusSuccessKey(cmdStatusKey.getValue());
		pro.setCdDescription(descField.getValue());
		if (update) {
			QueryWrapper<CommonProjectMgmt> wrap = new QueryWrapper<CommonProjectMgmt>();
			wrap.eq("id_host", addr.getIdHost()).eq("id_project",pro.getIdProject());
			CommonProjectMgmt p = commonProjectMapper.selectOne(wrap);
			if (null != p) {
				Notification.show("项目ID不能重复！", Notification.Type.WARNING_MESSAGE);
				return;
			}else {
				commonProjectMapper.insert(pro);
			}
		} else {
			UpdateWrapper<CommonProjectMgmt> wrap = new UpdateWrapper<>();
			wrap.eq("id_host", addr.getIdHost()).eq("id_project",pro.getIdProject());
			commonProjectMapper.update(pro,wrap);
		}
		QueryWrapper<CommonProjectMgmt> wrap2 = new QueryWrapper<CommonProjectMgmt>();
		wrap2.eq("id_host", addr.getIdHost());
		grid.setItems(commonProjectMapper.selectList(wrap2));
		win.close();
		Notification.show("保存成功", Notification.Type.WARNING_MESSAGE);
	}

	private void popWindowAddProject(boolean update, String idProject) {
		FormLayout lay = new FormLayout();
		lay.addStyleName("project-addproject-window");
		idProjectField = ComponentFactory.getStandardTtextField("项目ID");
		idProjectField.setRequiredIndicatorVisible(true);
		idProjectField.setWidth("370px");
		nameProjectField = ComponentFactory.getStandardTtextField("项目名称");
		nameProjectField.setRequiredIndicatorVisible(true);
		nameProjectField.setWidth("370px");
		nameProjectField.setPlaceholder("不可以为空");
		scriptPath = ComponentFactory.getStandardTtextField("脚本存放目录");
		scriptPath.setRequiredIndicatorVisible(true);
		scriptPath.setDescription("确保该目录脚本有执行权限");
		scriptPath.setWidth("370px");
		scriptPath.setPlaceholder("注：脚本存放目录,示例：/usr/local/nginx/sbin");
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
		cmdStatusKey = ComponentFactory.getStandardTtextField("状态检查关键字");
		cmdStatusKey.setPlaceholder("示例：nginx: master");
		cmdStatusKey.setWidth("370px");
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
		lay.addComponent(cmdStatusKey);
		lay.addComponent(descField);
		lay.addComponent(saveBtn);
		Label label = ComponentFactory.getStandardLabel("提示：上传默认上传到了脚本存放目录，可修改该路径实现<br>上传各类文件到指定目录");
		label.setContentMode(ContentMode.HTML);
		lay.addComponent(label);

		if (!update) {
			QueryWrapper<CommonProjectMgmt> wrap = new QueryWrapper<CommonProjectMgmt>();
			wrap.eq("id_host", addr.getIdHost()).eq("id_project",idProject);
			CommonProjectMgmt p = commonProjectMapper.selectOne(wrap);
			if (null == p) {
				Notification.show("未找到该项目，可能已被删除", Notification.Type.ERROR_MESSAGE);
				return;
			}
			idProjectField.setValue(p.getIdProject());
			nameProjectField.setValue(p.getNameProject() == null ? "" : p.getNameProject());
			idProjectField.setEnabled(false);
			scriptPath.setValue(p.getCdPath() == null ? "" : p.getCdPath());
			cmdStart.setValue(p.getCmdStart() == null ? "" : p.getCmdStart());
			cmdStop.setValue(p.getCmdStop() == null ? "" : p.getCmdStop());
			cmdReStart.setValue(p.getCmdRestart() == null ? "" : p.getCmdRestart());
			cmdRefresh.setValue(p.getCmdRefresh() == null ? "" : p.getCmdRefresh());
			cmdStatus.setValue(p.getCmdStatus() == null ? "" : p.getCmdStatus());
			cmdStatusKey.setValue(p.getCmdStatusSuccessKey() == null ? "" : p.getCmdStatusSuccessKey());
			descField.setValue(p.getCdDescription() == null ? "" : p.getCdDescription());
			classField.setValue(p.getCdTag() == null ? "" : p.getCdTag());
		}

		win = new Window("添加项目");
		win.setHeight("700px");
		win.setWidth("650px");
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

	public ConnectionInfo getAddr() {
		return addr;
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
