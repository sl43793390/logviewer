package com.so.component.util;

import java.util.Iterator;

import com.so.ui.LogCheckView;
import com.so.component.CommonComponent;
import com.so.component.ComponentUtil;
import com.vaadin.server.Page;
import com.vaadin.ui.Component;
import com.vaadin.ui.TabSheet;

public class TabSheetUtil {

	public static final String CAPTION_USER_MANAGEMENT = "用户管理";
	public static final String CAPTION_ADD_USER = "新增用户";
	public static final String CAPTION_MODIFY_USER = "修改用户";
	public static final String CAPTION_DETAIL_LIMIT = "查看详情";
	

	public static void closeCurrrentTab(String targetTab){
		
		ComponentUtil.getView().getMainTabsheet()
		.removeComponent(ComponentUtil.getView().getMainTabsheet().getSelectedTab());
    	
		LogCheckView bean = ComponentUtil.applicationContext.getBean(LogCheckView.class);
		Iterator<Component> tabComponents = bean.getMainTabsheet().iterator();
		while (tabComponents.hasNext()) {
			Component next = tabComponents.next();
			if (targetTab.equals(bean.getMainTabsheet().getTab(next).getCaption())) {
				ComponentUtil.getView().getMainTabsheet().
		    	setSelectedTab(next);
				return;
			}
		}		
	}
	
	public static void selectTargetTab(String targetTab){
		
		LogCheckView bean = ComponentUtil.applicationContext.getBean(LogCheckView.class);
		Iterator<Component> tabComponents = bean.getMainTabsheet().iterator();
		while (tabComponents.hasNext()) {
			Component next = tabComponents.next();
			if (targetTab.equals(bean.getMainTabsheet().getTab(next).getCaption())) {
				ComponentUtil.getView().getMainTabsheet().
				setSelectedTab(next);
				return;
			}
		}		
	}
	
	public static void closeTagetTab(String targetTab){
    	
		LogCheckView bean = ComponentUtil.applicationContext.getBean(LogCheckView.class);
		Iterator<Component> tabComponents = bean.getMainTabsheet().iterator();
		while (tabComponents.hasNext()) {
			Component next = tabComponents.next();
			if (targetTab.equals(bean.getMainTabsheet().getTab(next).getCaption())) {
				ComponentUtil.getView().getMainTabsheet()
				.removeComponent(next);
				return;
			}
		}		
	}
	
	public static TabSheet getMainTabsheet(){
		LogCheckView bean = ComponentUtil.applicationContext.getBean(LogCheckView.class);
		TabSheet mainTabsheet = bean.getMainTabsheet();
		return mainTabsheet;
	}
	
	public static void closeCurrrentTab(){
		CommonComponent aa=(CommonComponent)ComponentUtil.getView().getMainTabsheet().getSelectedTab();
		Page.getCurrent().removeBrowserWindowResizeListener(aa.reSizeListener);
		getMainTabsheet().removeComponent(ComponentUtil.getView().getMainTabsheet().getSelectedTab());
	}
	
	public static Component getComponent(String targetTab){
		Component componet = null;
		LogCheckView bean = ComponentUtil.applicationContext.getBean(LogCheckView.class);
		Iterator<Component> tabComponents = bean.getMainTabsheet().iterator();
		while (tabComponents.hasNext()) {
			Component componetTemp = tabComponents.next();
			if (targetTab.equals(bean.getMainTabsheet().getTab(componetTemp).getCaption())) {
				componet = componetTemp;
				return componet;
			}
		}		
		return componet;
	}
	public static Component getComponentLike(String targetTab){
		Component componet = null;
		LogCheckView bean = ComponentUtil.applicationContext.getBean(LogCheckView.class);
		Iterator<Component> tabComponents = bean.getMainTabsheet().iterator();
		while (tabComponents.hasNext()) {
			Component componetTemp = tabComponents.next();
			if (bean.getMainTabsheet().getTab(componetTemp).getCaption().contains(targetTab)) {
				componet = componetTemp;
				return componet;
			}
		}
		return componet;
	}
	
	public static boolean checkComponent(String tabCaption){
		LogCheckView bean = ComponentUtil.applicationContext.getBean(LogCheckView.class);
		Iterator<Component> tabComponents = bean.getMainTabsheet().iterator();
		while (tabComponents.hasNext()) {
			Component componet = tabComponents.next();
			if (tabCaption.equals(bean.getMainTabsheet().getTab(componet).getCaption())) {
				return true;
			}
		}		
		return false;
	}
	public static boolean checkComponentExists(TabSheet tabSheet,String tabCaption){
		Iterator<Component> tabComponents = tabSheet.iterator();
		while (tabComponents.hasNext()) {
			Component componet = tabComponents.next();
			if (tabCaption.equals(tabSheet.getTab(componet).getCaption())) {
				return true;
			}
		}		
		return false;
	}
	
	public static void navigateTo(String tabCaption){
		LogCheckView bean = ComponentUtil.applicationContext.getBean(LogCheckView.class);
		Iterator<Component> tabComponents = bean.getMainTabsheet().iterator();
		while (tabComponents.hasNext()) {
			Component componet = tabComponents.next();
			if (tabCaption.equals(bean.getMainTabsheet().getTab(componet).getCaption())) {
				ComponentUtil.getView().getMainTabsheet().
		    	setSelectedTab(componet);
			}
		}		
	}
	public static void navigateTo(TabSheet tabSheet,String tabCaption){
		Iterator<Component> tabComponents = tabSheet.iterator();
		while (tabComponents.hasNext()) {
			Component componet = tabComponents.next();
			if (tabCaption.equals(tabSheet.getTab(componet).getCaption())) {
				tabSheet.setSelectedTab(componet);
			}
		}		
	}
	
}
