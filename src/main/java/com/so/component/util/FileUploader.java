package com.so.component.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;

import com.so.component.CommonComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.so.component.ComponentUtil;
import com.so.entity.ProjectList;
import com.so.mapper.ProjectsMapper;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Upload.FailedEvent;
import com.vaadin.ui.Upload.FailedListener;
import com.vaadin.ui.Upload.Receiver;
import com.vaadin.ui.Upload.SucceededEvent;
import com.vaadin.ui.Upload.SucceededListener;

// Implement both receiver that saves upload in a file and
// listener for successful upload
public class FileUploader implements Receiver, SucceededListener, FailedListener {
	private static final long serialVersionUID = -2426493461264140038L;
	public File file;
	private String keypath;
	private String parentPath;
	private String idProject;
	private CommonComponent component;
	
	private static final Logger log = LoggerFactory.getLogger(FileUploader.class);

	@Override
	public OutputStream receiveUpload(String filename, String mimeType) {
		if (null != parentPath) {
			keypath = parentPath+ File.separator + filename;
		}else {
			String property = System.getProperty("user.dir");
			keypath = property + File.separator + filename;
		}
		log.info("上传文件路径为："+keypath);
		file = new File(keypath);
		FileOutputStream fileStream = null;
		try {
			fileStream = new FileOutputStream(file);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		}
		return fileStream;
	}

	@Override
	public void uploadFailed(FailedEvent event) {
		Notification.show("提示：", "上传文件失败，请重新上传", Notification.Type.WARNING_MESSAGE);
	}
	@Override
	public void uploadSucceeded(SucceededEvent event) {
		// 这个接收器在「本地文件管理」「上传秘钥」等场景下没有 idProject，
		// 原来无条件去查 project_list，查不到时 selectById.setJarName(...) 会直接 NPE
		boolean hasProject = null != idProject && idProject.trim().length() > 0;
		if (null != file && hasProject) {
			try {
				ProjectsMapper projectsMapper = ComponentUtil.applicationContext.getBean(ProjectsMapper.class);
				QueryWrapper<ProjectList> queryWrapper = new QueryWrapper<ProjectList>();
				queryWrapper.eq("id_host", "localhost").eq("id_project", idProject);
				ProjectList selectById = projectsMapper.selectOne(queryWrapper);
				if (null == selectById) {
					log.warn("project_list 中未找到 id_project={}，跳过 jar 名称回写", idProject);
				} else if (file.getName().endsWith("jar")) {
					selectById.setJarName(file.getName());
					UpdateWrapper<ProjectList> up = new UpdateWrapper<ProjectList>();
					HashMap<String, String> map = new HashMap<String, String>();
					map.put("id_host", "localhost");
					map.put("id_project", idProject);
					up.allEq(map);
					projectsMapper.update(selectById, up);
				}
			} catch (Exception e) {
				log.error("上传后回写项目信息失败：{}", e.getMessage(), e);
			}
		}
		if (null != component){
			component.initLayout();
			component.initContent();
			component.registerHandler();
		}
		Notification.show("提示：", "上传文件成功", Notification.Type.WARNING_MESSAGE);
		log.info("上传成功");
	}

	public File getFile() {
		return file;
	}

	public void setFile(File file) {
		this.file = file;
	}

	public String getKeypath() {
		return keypath;
	}

	public void setKeypath(String keypath) {
		this.keypath = keypath;
	}

	public String getIdProject() {
		return idProject;
	}

	public void setIdProject(String idProject) {
		this.idProject = idProject;
	}

	public String getParentPath() {
		return parentPath;
	}

	public void setParentPath(String parentPath) {
		this.parentPath = parentPath;
	}
	public void setComponent(CommonComponent component) {
		this.component = component;
	}
}