package com.so.component.management;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.so.component.util.*;
import com.so.ui.LoginView;
import com.so.util.Constants;
import com.vaadin.ui.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.so.component.CommonComponent;
import com.so.entity.ProjectList;
import com.so.mapper.ProjectsMapper;
import com.so.ui.ComponentFactory;
import com.so.util.Util;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;

@Service
@Scope("prototype")
public class JarMgmtComponent extends CommonComponent {

	private static final Logger log = LoggerFactory.getLogger(JarMgmtComponent.class);
	private static final long serialVersionUID = -5516121570034623010L;
	private Panel mainPanel;
	private VerticalLayout contentLayout;

	public FileUploader loader;
	@Autowired
	private ProjectsMapper projectsMapper;
	private TextField pathField;
	private TextField classField;
	private TextField startField;
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
		//将组件加入到layout
		QueryWrapper<ProjectList> queryWrapper = new QueryWrapper<ProjectList>();
		queryWrapper.eq("id_host", "localhost");
		List<ProjectList> selectList = projectsMapper.selectList(queryWrapper);
		grid = new Grid<ProjectList>();
		contentLayout.addComponent(grid);
		contentLayout.setExpandRatio(absoluteLayout, 1);
		contentLayout.setExpandRatio(grid, 10);
		
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
				try {
					//在启动前检查当前jar包路径下是否有脚本server.sh,没有就生成一个
					checkAddShell(p);
					if (StringUtils.isNotBlank(p.getJvmParam()) || StringUtils.isNotBlank(p.getJarParam())) {
						log.info("使用默认命令启动jar包,带用户指定参数");
						// Util.executeSellScript("sh server.sh start " + p.getNameProject(), p.getCdParentPath());
						Notification.show("正在启动中，请稍候......", Notification.Type.WARNING_MESSAGE);
						new Thread(new Runnable() {
							@Override
							public void run() {
								// TODO Auto-generated method stub
								if (null != p.getJvmParam() && null != p.getJarParam()) {
									String cmd = "nohup java -jar "+p.getJvmParam().trim() + " " + p.getNameProject() +  " " +p.getJarParam() +" 2>&1 > app.log &";
									log.info("执行命令："+cmd);
									Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(),cmd));
								}else if(StringUtils.isNotBlank(p.getJvmParam())){
									String cmd = "nohup java -jar "+p.getJvmParam().trim() +  " " +p.getNameProject() +" 2>&1 > app.log &";
									log.info("执行命令："+cmd);
									Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(),cmd));
								}else if (StringUtils.isNotBlank(p.getJarParam())) {
									String cmd = "nohup java -jar "+ p.getNameProject().trim() +" 2>&1 > app.log &";
									log.info("执行命令："+cmd);
									Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(),cmd));
								}
							}
						}).start();
					} else if (StrUtil.isNotBlank(p.getCdCommand())) {
						log.info("使用自定义的命令启动jar包");
						new Thread(new Runnable() {
							@Override
							public void run() {
								List<String> executeNewFlow = Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(),p.getCdCommand()));
								log.info(executeNewFlow.toString());
							}
						}).start();
					} else {
						//命令和参数均未配置使用默认脚本启动
						new Thread(new Runnable() {
							@Override
							public void run() {
								List<String> lres = Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(),"chmod 777 server.sh;sh server.sh start "+p.getNameProject()));
								log.info(lres.toString());
							}
						}).start();
					}
				} catch (Exception e1) {
					e1.printStackTrace();
					Notification.show("启动命令执行失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
				}
				Notification.show("命令已经执行，请到日志搜索界面搜索并查看日志", Notification.Type.WARNING_MESSAGE);
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
					Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(),
							"kill -9 `ps -ef | grep " + p.getNameProject() + " | grep -v grep | awk '{print $2}'`"));
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
					List<String> executeNewFlow = Util.executeNewFlow(Arrays.asList("cd " + p.getCdParentPath(), "ps -ef | grep java"));
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
					Notification.show("停止服务失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
					e1.printStackTrace();
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
				} catch (Exception e1) {
					Notification.show("停止服务失败，请注意查看日志", Notification.Type.WARNING_MESSAGE);
					e1.printStackTrace();
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
					log.info(p.toString());
					popWindowAddProject(false, p.getIdProject());
				} catch (Exception e1) {
					Notification.show("系统繁忙", Notification.Type.WARNING_MESSAGE);
					e1.printStackTrace();
				}
			});
			return b;
		}).setCaption("修改");
		grid.addComponentColumn(p -> {
			loader = new FileUploader();
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
	}

	private void checkAddShell(ProjectList p) {
		String path = p.getCdParentPath()+File.separator+"server.sh";
		log.info(path);
		File file = new File(path);
		if (!file.exists()) {
			log.warn("开始生成默认脚本。。。。。");
			List<String> servershell = Util.getConfigFileAsLineByClasspathResource("server.sh");
			FileUtil.writeLines(servershell, file, "utf-8");
//			Util.executeLinuxCmd("chmod 755 "+p.getCdParentPath()+File.pathSeparator+"server.sh");
			Util.executeNewFlow(Arrays.asList("cd "+p.getCdParentPath(),"chmod 755 server.sh"));
		}else {
			log.warn("server.sh 文件已经存在，跳过生成。。。");
		}
	}

	private void saveOrUpdateProject(boolean update) {

		ProjectList pro = new ProjectList();
		if (idProjectField.getValue() == null || pathField.getValue() == null) {
			Notification.show("项目ID、项目所在路径不能为空！", Notification.Type.WARNING_MESSAGE);
			return;
		}
		pro.setIdHost("localhost");
		pro.setIdProject(idProjectField.getValue());
		pro.setNameProject(nameProjectField.getValue());
		pro.setCdParentPath(pathField.getValue());
		pro.setCdTag(classField.getValue());
		pro.setCdCommand(startField.getValue());
		pro.setJvmParam(jvmParam.getValue());
		pro.setJarParam(jarParam.getValue());
		pro.setCdDescription(descField.getValue());
		if (update) {
			projectsMapper.insert(pro);
		} else {
			UpdateWrapper<ProjectList> up = new UpdateWrapper<ProjectList>();
			HashMap<String, String> map = new HashMap<String, String>();
			map.put("id_host", pro.getIdHost());
			map.put("id_project", pro.getIdProject());
			up.allEq(map);
			projectsMapper.update(pro, up);
		}
		QueryWrapper<ProjectList> queryWrapper = new QueryWrapper<ProjectList>();
		queryWrapper.eq("id_host", "localhost");
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
		idProjectField.setWidth("90%");
		nameProjectField = ComponentFactory.getStandardTtextField("项目名称");
		nameProjectField.setWidth("90%");
		nameProjectField.setPlaceholder("输入jar、war包名称：xxx.jar");
		pathField = ComponentFactory.getStandardTtextField("项目所在路径");
		pathField.setWidth("90%");
		pathField.setPlaceholder("注：不包含jar包名称");
		classField = ComponentFactory.getStandardTtextField("项目tag");
		classField.setWidth("90%");
		classField.setPlaceholder("tag用于对项目进行分类");
		startField = ComponentFactory.getStandardTtextField("启动命令");
		startField.setWidth("90%");
		startField.setPlaceholder("请输入启动命令，可以为空");
		jvmParam = ComponentFactory.getTextArea("JVM参数");
		jvmParam.setHeight("100px");
		jvmParam.setWidth("90%");
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
			ProjectList p = projectsMapper.selectById(idProject);
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
		win.setHeight("600px");
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
		searchBtn.addClickListener(e ->{
			String name = nameField.getValue();
			String tag = tagfield.getValue();
			if (null == name && tag == null) {
				Notification.show("请输入搜索条件", Notification.Type.WARNING_MESSAGE);
				return;
			}
			QueryWrapper<ProjectList> query = new QueryWrapper<ProjectList>();
			query.eq("id_host", "localhost");
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

}
