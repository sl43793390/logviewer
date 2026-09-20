package com.so.component.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;

import com.so.component.CommonComponent;
import com.vaadin.ui.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jcraft.jsch.Session;
import com.so.component.ComponentUtil;
import com.so.entity.ConnectionInfo;
import com.so.entity.ProjectList;
import com.so.mapper.ProjectsMapper;
import com.so.util.MyJSchUtil;
import com.vaadin.ui.Upload.FailedEvent;
import com.vaadin.ui.Upload.FailedListener;
import com.vaadin.ui.Upload.Receiver;
import com.vaadin.ui.Upload.SucceededEvent;
import com.vaadin.ui.Upload.SucceededListener;

// Implement both receiver that saves upload in a file and
// listener for successful upload
public class RemoteFileUploader implements Receiver, SucceededListener, FailedListener {
	private static final long serialVersionUID = -2426493461264140038L;
	public File file;
	private String keypath;
	private String parentPath;
	private String idProject;
	private boolean remoteFlag;
	private ConnectionInfo addr;
	private CommonComponent component;
	/** 是否顺带把 server.sh 部署到目标目录（上传 war 包到 tomcat webapps 时不需要） */
	private boolean uploadServerScript = true;
	private Session session;
	
	private static final Logger log = LoggerFactory.getLogger(RemoteFileUploader.class);

	
	public RemoteFileUploader() {
		super();
	}

	public RemoteFileUploader(boolean remoteFlag) {
		super();
		this.remoteFlag = remoteFlag;
	}

	@Override
	public OutputStream receiveUpload(String filename, String mimeType) {
		String property = System.getProperty("user.dir");
		keypath = property + File.separator + filename;
		log.info("上传临时文件路径为："+keypath);
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
		// 数据回写 + 界面刷新必须在 Vaadin 请求线程里做
		updateProjectJarName();
		if (null != component){
			component.initLayout();
			component.initContent();
			component.registerHandler();
		}
		if (!remoteFlag) {
			Notification.show("提示：", "上传文件成功", Notification.Type.WARNING_MESSAGE);
			log.info("上传成功");
			return;
		}
		// 传输是纯网络 IO。原实现直接在这里 sleep(4s) 再同步 SFTP，
		// 整个请求线程（含会话锁）被占住 4 秒以上，页面全卡死。改为后台线程，
		// 完成后再通过 UI.access 回到 UI 线程给出结果提示。
		final UI ui = UI.getCurrent();
		final File localFile = file;
		final String remoteDir = parentPath;
		Notification.show("提示：", "文件已接收，正在上传到远程服务器，请稍候……", Notification.Type.WARNING_MESSAGE);
		new Thread(new Runnable() {
			@Override
			public void run() {
				boolean success = false;
				String failReason = null;
				try {
					try (FileInputStream in = new FileInputStream(localFile)) {
						if (!MyJSchUtil.uploadFile(session, in, remoteDir, localFile.getName())) {
							throw new IOException("SFTP 上传返回失败：" + remoteDir + "/" + localFile.getName());
						}
					}
					if (uploadServerScript) {
						File script = new File(System.getProperty("user.dir") + File.separator + "bin" + File.separator + "server.sh");
						if (!script.exists()) {
							throw new IOException("本地脚本不存在：" + script.getAbsolutePath());
						}
						try (FileInputStream in = new FileInputStream(script)) {
							if (!MyJSchUtil.uploadFile(session, in, remoteDir, "server.sh")) {
								throw new IOException("SFTP 上传 server.sh 失败");
							}
						}
						// parentPath 是远端 Linux 路径，必须用 "/" 拼接。
						// 原来的 File.separator 在 Windows 上会拼出 /home/app\server.sh
						MyJSchUtil.remoteExecute(session, "chmod 777 " + remoteDir + "/server.sh");
					}
					success = true;
					log.info("远程文件上传成功=====");
				} catch (Exception e) {
					failReason = e.getMessage();
					log.error("远程文件上传失败=====", e);
				} finally {
					if (!localFile.delete()) {
						log.warn("本地临时文件删除失败：{}", localFile.getAbsolutePath());
					}
					final boolean ok = success;
					final String reason = failReason;
					if (null != ui) {
						ui.access(new Runnable() {
							@Override
							public void run() {
								if (ok) {
									Notification.show("提示：", "已上传到远程服务器", Notification.Type.HUMANIZED_MESSAGE);
								} else {
									Notification.show("提示：", "上传到远程服务器失败：" + reason,
											Notification.Type.ERROR_MESSAGE);
								}
							}
						});
					}
				}
			}
		}, "remote-upload-" + localFile.getName()).start();
	}

	/**
	 * 把上传的 jar 包名回写到 project_list。<br>
	 * 查不到记录（例如 tomcat 管理的项目不在 project_list 里）时跳过，
	 * 原来的 {@code selectById.setJarName(...)} 会直接抛 NPE。
	 */
	private void updateProjectJarName() {
		if (null == file || null == idProject || null == addr) {
			return;
		}
		if (!file.getName().endsWith("jar")) {
			return;
		}
		try {
			ProjectsMapper projectsMapper = ComponentUtil.applicationContext.getBean(ProjectsMapper.class);
			QueryWrapper<ProjectList> queryWrapper = new QueryWrapper<ProjectList>();
			queryWrapper.eq("id_host", addr.getIdHost()).eq("id_project", idProject);
			ProjectList selectById = projectsMapper.selectOne(queryWrapper);
			if (null == selectById) {
				log.warn("项目表中未找到 id_host={} id_project={} 的记录，跳过 jar 名称回写", addr.getIdHost(), idProject);
				return;
			}
			selectById.setJarName(file.getName());
			UpdateWrapper<ProjectList> up = new UpdateWrapper<ProjectList>();
			HashMap<String, String> map = new HashMap<String, String>();
			map.put("id_host", addr.getIdHost());
			map.put("id_project", idProject);
			up.allEq(map);
			projectsMapper.update(selectById, up);
		} catch (Exception e1) {
			log.error("回写 jar 名称失败", e1);
		}
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

	public boolean isRemoteFlag() {
		return remoteFlag;
	}

	public void setRemoteFlag(boolean remoteFlag) {
		this.remoteFlag = remoteFlag;
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public boolean isUploadServerScript() {
		return uploadServerScript;
	}

	public void setUploadServerScript(boolean uploadServerScript) {
		this.uploadServerScript = uploadServerScript;
	}

	public ConnectionInfo getAddr() {
		return addr;
	}

	public void setAddr(ConnectionInfo addr) {
		this.addr = addr;
	}

	public Component getComponent() {
		return component;
	}

	public void setComponent(CommonComponent component) {
		this.component = component;
	}
}