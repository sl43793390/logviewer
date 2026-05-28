package com.so.component.remote;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.so.component.ComponentUtil;
import com.so.component.util.*;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.ui.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.so.component.CommonComponent;
import com.so.entity.ConnectionInfo;
import com.so.entity.ProjectList;
import com.so.mapper.ProjectsMapper;
import com.so.ui.ComponentFactory;
import com.so.util.MyJSchUtil;

import cn.hutool.core.util.StrUtil;

/**
 * 远程管理其它机器的jar
 * @author Administrator
 *
 */
@Service
@Scope("prototype")
public class RemoteJarMgmtComponent extends CommonComponent {

	private static final Logger log = LoggerFactory.getLogger(RemoteJarMgmtComponent.class);
	private static final long serialVersionUID = -5516121570034623010L;
	private Panel mainPanel;
	private VerticalLayout contentLayout;
	private ChannelSftp channel;
	private ConnectionInfo addr;
	private Session jschSession;
	public RemoteFileUploader loader;
	@Autowired
	private ProjectsMapper projectsMapper;
	private TextField pathField;
	private TextField classField;
	private TextArea startField;
	private TextArea jvmParam;
	private TextField jarParam;
	private TextField idProjectField;
	private TextField descField;
	private Window win;
	private TextField nameProjectField;
	private Grid<ProjectList> grid;
	private Button searchBtn;
	private TextField nameField;
	private TextField tagfield;

	@Override
	public void initLayout() {
		mainPanel = new Panel();
		setCompositionRoot(mainPanel);
		contentLayout = new VerticalLayout();
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
		//将组件加入到layout
		QueryWrapper<ProjectList> queryWrapper = new QueryWrapper<ProjectList>();
		queryWrapper.eq("id_host", addr.getIdHost());
		List<ProjectList> selectList = projectsMapper.selectList(queryWrapper);
		grid = new Grid<ProjectList>();
		contentLayout.addComponent(grid);
		contentLayout.setExpandRatio(grid, 1);
//		contentLayout.setExpandRatio(grid, 10);
		
		grid.setWidthFull();
		grid.setHeightFull();
		grid.setItems(selectList);
		grid.addColumn(ProjectList::getIdProject).setCaption("ID");
		grid.addColumn(ProjectList::getCdTag).setCaption("tag");
		grid.addColumn(ProjectList::getNameProject).setCaption("名称");
		grid.addColumn(ProjectList::getCdDescription).setCaption("描述");
		/** 使用componentColumn生成列的button ，相比render 更为灵活可以单独设置button的各种属性 */
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("启动");
			b.addStyleName("");
			b.addClickListener(e -> {
					//在启动前检查当前jar包路径下是否有脚本server.sh,没有就生成一个
//					checkAddShell(p);
					if (StringUtils.isNotBlank(p.getJvmParam()) || StringUtils.isNotBlank(p.getJarParam())) {
						log.info("使用默认命令启动jar包,带用户指定参数");
						// Util.executeSellScript("sh server.sh start " + p.getNameProject(), p.getCdParentPath());
						Notification.show("正在启动中，请稍候......", Notification.Type.WARNING_MESSAGE);
						new Thread(new Runnable() {
							@Override
							public void run() {
								if (null != p.getJvmParam() && null != p.getJarParam()) {
									String cmd = "source /etc/profile;nohup java -jar "+p.getJvmParam().trim() + " " + p.getNameProject() +  " " +p.getJarParam() +" 2>&1 > app.log &";
									log.info("执行命令："+cmd);
									try {
										List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession, "cd " + p.getCdParentPath()+";"+cmd);
										log.info(remoteExecute.toString());
									} catch (JSchException e) {
										log.error(ExceptionUtils.getStackTrace(e));
										Notification.show("启动失败，请前往查看日志信息",Notification.Type.ERROR_MESSAGE);
									}
								}else if(StringUtils.isNotBlank(p.getJvmParam())){
									String cmd = "source /etc/profile;nohup java -jar "+p.getJvmParam().trim() +  " " +p.getNameProject() +" 2>&1 > app.log &";
									log.info("执行命令："+cmd);
									try {
										List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession, "cd " + p.getCdParentPath()+";"+cmd);
										log.info(remoteExecute.toString());
									} catch (JSchException e) {
										log.error(ExceptionUtils.getStackTrace(e));
										Notification.show("启动失败，请前往查看日志信息",Notification.Type.ERROR_MESSAGE);
									}
//									Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(),cmd));
								}else if (StringUtils.isNotBlank(p.getJarParam())) {
									String cmd = "source /etc/profile;nohup java -jar "+ p.getNameProject().trim() +" 2>&1 > app.log &";
									log.info("执行命令："+cmd);
									try {
										List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession, "cd " + p.getCdParentPath()+";"+cmd);
										log.info(remoteExecute.toString());
									} catch (JSchException e) {
										log.error(ExceptionUtils.getStackTrace(e));
										Notification.show("启动失败，请前往查看日志信息",Notification.Type.ERROR_MESSAGE);
									}
								}
							}
						}).start();
					} else if (StrUtil.isNotBlank(p.getCdCommand())) {
						log.info("使用配置命令启动jar包");
						new Thread(new Runnable() {
							@Override
							public void run() {
								try {
									List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession, "source /etc/profile;cd " + p.getCdParentPath()+";"+p.getCdCommand());
//									log.info(remoteExecute.toString());
								} catch (JSchException e) {
									log.error(ExceptionUtils.getStackTrace(e));
									Notification.show("启动失败，请前往查看日志信息",Notification.Type.ERROR_MESSAGE);
								}
							}
						}).start();
					} else {
						//命令和参数均未配置使用默认脚本启动
						log.info("用户未配置命令和参数，使用默认脚本启动jar包");
						new Thread(new Runnable() {
							@Override
							public void run() {
								try {
									//不加source /etc/profile就会报java找到不到命令
									List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession, "source /etc/profile;cd " + p.getCdParentPath()+
											";chmod 777 server.sh;sh server.sh start "+p.getNameProject());
									log.info(remoteExecute.toString());
								} catch (JSchException e2) {
									log.error(ExceptionUtils.getStackTrace(e2));
									Notification.show("启动失败，请前往查看日志信息",Notification.Type.ERROR_MESSAGE);
								}
							}
						}).start();
					}
				Notification.show("命令已经执行，请注意查看日志", Notification.Type.WARNING_MESSAGE);
				// 获取已经选择的行
				// Set<User> selectedItems = grid.getSelectedItems();
				// selectedItems.forEach(u -> System.out.println(u.getUserId()));
			});
			return b;
		}).setCaption("启动服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("停止");
			b.addClickListener(e -> {
				// 获取当前按钮对应行的对象
				log.info("使用默认脚本命令停止服务");
				try {
					// Util.executeSellScript("sh server.sh stop " + p.getNameProject(), p.getCdParentPath());
					try {
						List<String> remoteExecute = MyJSchUtil.remoteExecute(jschSession, "source /etc/profile;cd " + p.getCdParentPath()+";kill -9 `ps -ef | grep " + p.getNameProject() + " | grep -v grep | awk '{print $2}'`");
						log.info(remoteExecute.toString());
					} catch (JSchException e1) {
						e1.printStackTrace();
					}
//					Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(),
//							"kill -9 `ps -ef | grep " + p.getNameProject() + " | grep -v grep | awk '{print $2}'`"));
				} catch (Exception e1) {
					Notification.show("停止服务失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
					e1.printStackTrace();
				}
				Notification.show("服务已经停止", Notification.Type.WARNING_MESSAGE);
			});
			return b;
		}).setCaption("停止服务");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getStandardButton("查看状态");
			b.addClickListener(e -> {
				try {
					// boolean runningStasus = Util.getRunningStasus("sh server.sh status " + p.getNameProject(), p.getCdParentPath());
					List<String> executeNewFlow = MyJSchUtil.remoteExecute(jschSession, "source /etc/profile;cd " + p.getCdParentPath()+";ps -ef | grep java");
//					List<String> executeNewFlow = Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(), "ps -ef | grep java"));
					boolean falg = false;
					for (String res : executeNewFlow) {
						if (res.contains(p.getNameProject())) {
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
					if (e1.getMessage().contains("Connection")){
						Notification.show("无法连接服务器，请使用客户端登录检查！", Notification.Type.WARNING_MESSAGE);
					}else{
						Notification.show("查看状态失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
					}
					e1.printStackTrace();
				}
			});
			return b;
		}).setCaption("查看状态");
		grid.addComponentColumn(p -> {
			Button b = ComponentFactory.getButtonWithColor("删除", ColorEnum.RED);
			b.addClickListener(e -> {
					if (!LoginView.checkPermission(Constants.DELETE)){
						Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
						return;
					}
					ConfirmationDialogPopupWindow yesNo = new ConfirmationDialogPopupWindow("确认", "请确认是否要删除！", "确定", "放弃", false);
					yesNo.addListener(new ConfirmationEventListener() {
						private static final long serialVersionUID = -8751718063979484449L;
						@Override
						protected void confirmed(ConfirmationEvent event) {
							Map<String, Object> map = new HashMap<String, Object>();
							map.put("id_host", p.getIdHost());
							map.put("id_project",p.getIdProject());
							projectsMapper.deleteByMap(map);
							grid.setItems(projectsMapper.selectList(new QueryWrapper<ProjectList>()));
						}
						@Override
						protected void rejected(ConfirmationEvent event) {
							super.rejected(event);
							return;
						}
					});
					yesNo.showConfirmation();
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
//			先临时上传到本地，然后开启一个线程上传到远程服务器 TODO
			loader = new RemoteFileUploader();
			loader.setRemoteFlag(true);//远程上传多增加参数
			loader.setSession(jschSession);//远程上传多增加参数
			loader.setAddr(addr);
			loader.setComponent(this);
			loader.setParentPath(p.getCdParentPath());
			loader.setIdProject(p.getIdProject());
			Upload upload = new Upload("上传", loader);
			upload.setImmediateMode(true);
			upload.addStartedListener(event ->{
				if (!LoginView.checkPermission(Constants.UPLOAD)){
					Notification.show("权限不足，请联系管理员", Notification.Type.WARNING_MESSAGE);
					throw new RuntimeException("权限不足，终止上传");
				}
			});
			upload.setButtonCaption("上传");
			upload.setHeight("30px");
			upload.addSucceededListener(loader);
			return upload;
		}).setCaption("上传jar包/脚本");
		grid.addComponentColumn(p ->{
			Button b = ComponentFactory.getStandardButton("打开目录");
			b.addClickListener(e -> {
				RemoteFileMgmtComponent fileMgmtComponent = ComponentUtil.applicationContext.getBean(RemoteFileMgmtComponent.class);
				fileMgmtComponent.setAddr(addr);
				fileMgmtComponent.setHostName(addr.getIdHost());
				fileMgmtComponent.setJschSession(jschSession);
				fileMgmtComponent.initLayout();
				fileMgmtComponent.initContent();
				fileMgmtComponent.registerHandler();
				fileMgmtComponent.jumpPath(p.getCdParentPath());
				TabSheetUtil.getMainTabsheet().addTab(fileMgmtComponent,p.getIdProject()).setClosable(true);
				TabSheetUtil.getMainTabsheet().setSelectedTab(fileMgmtComponent);
			});
			return b;
		}).setCaption("打开目录");
	}

//	private void checkAddShell(ProjectList p) {
//		String path = p.getCdParentPath()+File.separator+"server.sh";
//		log.info(path);
//		File file = new File(path);
//		if (!file.exists()) {
//			log.warn("开始生成默认脚本。。。。。");
//			List<String> servershell = Util.getConfigFileAsLineByClasspathResource("server.sh");
//			FileUtil.writeLines(servershell, file, "utf-8");
////			Util.executeLinuxCmd("chmod 755 "+p.getCdParentPath()+File.pathSeparator+"server.sh");
//			Util.executeNewFlow(Arrays.asList("cd "+p.getCdParentPath(),"chmod 755 server.sh"));
//		}else {
//			log.warn("server.sh 文件已经存在，跳过生成。。。");
//		}
//	}

	private void saveOrUpdateProject(boolean update) {

		ProjectList pro = new ProjectList();
		if (idProjectField.getValue() == null || pathField.getValue() == null) {
			Notification.show("项目ID、项目所在路径不能为空！", Notification.Type.WARNING_MESSAGE);
			return;
		}
		pro.setIdHost(addr.getIdHost());
		String id = StringUtils.removeEnd(idProjectField.getValue(), "/");
		pro.setIdProject(id);
		pro.setNameProject(nameProjectField.getValue());
		pro.setCdParentPath(pathField.getValue());
		pro.setCdTag(classField.getValue());
		if (StrUtil.isNotEmpty(startField.getValue()) && !startField.getValue().startsWith("nohup")){
			Notification.show("为避免将被启动的jar包的全部日志都打印到本地，建议增加nohup在后台启动！", Notification.Type.WARNING_MESSAGE);
			return;
		}
		if (!startField.getValue().endsWith("&")){
			Notification.show("为避免将被启动的jar包的全部日志都打印到本地，建议在后台启动增加&！", Notification.Type.WARNING_MESSAGE);
			return;
		}
		pro.setCdCommand(startField.getValue());
		pro.setJvmParam(jvmParam.getValue());
		pro.setJarParam(jarParam.getValue());
		pro.setCdDescription(descField.getValue());
		if (update) {
			QueryWrapper<ProjectList> queryWrapper = new QueryWrapper<ProjectList>();
			queryWrapper.eq("id_host", addr.getIdHost()).eq("id_project",pro.getIdProject());
			ProjectList selectOne = projectsMapper.selectOne(queryWrapper);
			if (null != selectOne) {
				Notification.show("项目ID不能重复！", Notification.Type.WARNING_MESSAGE);
				return;
			}else {
				projectsMapper.insert(pro);
			}
		} else {
			UpdateWrapper<ProjectList> up = new UpdateWrapper<ProjectList>();
			HashMap<String, String> map = new HashMap<String, String>();
			map.put("id_host", pro.getIdHost());
			map.put("id_project", pro.getIdProject());
			up.allEq(map);
			projectsMapper.update(pro, up);
		}
		QueryWrapper<ProjectList> queryWrapper = new QueryWrapper<ProjectList>();
		queryWrapper.eq("id_host", addr.getIdHost());
		grid.setItems(projectsMapper.selectList(queryWrapper));
		win.close();
		Notification.show("保存成功", Notification.Type.WARNING_MESSAGE);
		return;
	}

	private void popWindowAddProject(boolean update, String idProject) {
		FormLayout lay = new FormLayout();
		lay.setWidth("95%");
		lay.addStyleName("project-addproject-window");
		idProjectField = ComponentFactory.getStandardTtextField("项目ID");
		idProjectField.setRequiredIndicatorVisible(true);
		idProjectField.setWidth("90%");
		nameProjectField = ComponentFactory.getStandardTtextField("项目名称");
		nameProjectField.setWidth("90%");
		nameProjectField.setRequiredIndicatorVisible(true);
		nameProjectField.setPlaceholder("输入jar、war包名称：xxx.jar");
		pathField = ComponentFactory.getStandardTtextField("项目所在路径");
		pathField.setWidth("90%");
		pathField.setRequiredIndicatorVisible(true);
		pathField.setPlaceholder("注：不包含jar包名称");
		classField = ComponentFactory.getStandardTtextField("项目tag");
		classField.setWidth("90%");
		classField.setPlaceholder("tag用于对项目进行分类");
		startField = ComponentFactory.getTextArea("启动命令");
		startField.setHeight("100px");
		startField.setWidth("90%");
		startField.setPlaceholder("输入启动命令，可以为空");
		jvmParam = ComponentFactory.getTextArea("JVM参数");
		jvmParam.setHeight("100px");
		jvmParam.setWidth("90%");
		jvmParam.setPlaceholder("输入JVM参数，可以为空");
		jarParam = ComponentFactory.getStandardTtextField("jar包参数");
		jarParam.setWidth("90%");
		descField = ComponentFactory.getStandardTtextField("项目描述");
		descField.setWidth("90%");
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
		lay.addComponent(pathField);
		lay.addComponent(classField);
		lay.addComponent(startField);
		lay.addComponent(jvmParam);
		lay.addComponent(jarParam);
		lay.addComponent(descField);
		lay.addComponent(saveBtn);

		if (!update) {
			QueryWrapper<ProjectList> queryWrapper = new QueryWrapper<ProjectList>();
			queryWrapper.eq("id_host", addr.getIdHost()).eq("id_project",idProject);
			ProjectList p = projectsMapper.selectOne(queryWrapper);
			idProjectField.setValue(p.getIdProject());
			nameProjectField.setValue(p.getNameProject() == null ? "" : p.getNameProject());
			idProjectField.setEnabled(false);
			pathField.setValue(p.getCdParentPath() == null ? "" : p.getCdParentPath());
			startField.setValue(p.getCdCommand() == null ? "" : p.getCdCommand());
			jvmParam.setValue(p.getJvmParam() == null ? "" : p.getJvmParam());
			jarParam.setValue(p.getJarParam() == null ? "" : p.getJarParam());
			descField.setValue(p.getCdDescription() == null ? "" : p.getCdDescription());
			classField.setValue(p.getCdTag() == null ? "" : p.getCdTag());
		}

		win = new Window("添加项目");
		win.setHeight("650px");
		win.setWidth("800px");
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
		searchBtn.addClickListener(e ->{
			String name = nameField.getValue();
			String tag = tagfield.getValue();
			if (null == name && tag == null) {
				List<ProjectList> selectByMap = projectsMapper.selectList(new QueryWrapper<>());
				grid.setItems(selectByMap);
				return;
			}
			QueryWrapper<ProjectList> query = new QueryWrapper<ProjectList>();
			query.eq("id_host", addr.getIdHost());
			if (null != name) {
				query.like("name_project", name);
			}
			if (null != tag) {
				query.like("cd_tag", tag);
			}
			List<ProjectList> selectByMap = projectsMapper.selectList(query);
			grid.setItems(selectByMap);
		});

	}

	public ChannelSftp getChannel() {
		return channel;
	}

	public void setChannel(ChannelSftp channel) {
		this.channel = channel;
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
