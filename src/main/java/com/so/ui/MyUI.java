package com.so.ui;

import javax.servlet.annotation.WebServlet;

import org.springframework.beans.factory.annotation.Autowired;

import com.vaadin.annotations.PreserveOnRefresh;
import com.vaadin.annotations.Push;
import com.vaadin.annotations.Theme;
import com.vaadin.annotations.VaadinServletConfiguration;
import com.vaadin.annotations.Widgetset;
import com.vaadin.navigator.Navigator;
import com.vaadin.navigator.ViewChangeListener;
import com.vaadin.server.VaadinRequest;
import com.vaadin.server.VaadinServlet;
import com.vaadin.server.VaadinSession;
import com.vaadin.shared.ui.ui.Transport;
import com.vaadin.spring.annotation.SpringUI;
import com.vaadin.spring.navigator.SpringViewProvider;
import com.vaadin.ui.UI;

/**
 * This UI is the application entry point. A UI may either represent a browser window 
 * (or tab) or some part of a html page where a Vaadin application is embedded.
 * <p>
 * The UI is initialized using {@link #init(VaadinRequest)}. This method is intended to be 
 * overridden to add component to the user interface and initialize non-component functionality.
 */
@Theme("mytheme")
@Widgetset(value="com.so.AppWidgetset")
@PreserveOnRefresh
@Push
@SpringUI
public class MyUI extends UI {

	/** 登录页 view 名 */
	public static final String LOGIN_VIEW = "loginView";
	/** 登录成功后的主页面 view 名 */
	public static final String MAIN_VIEW = "logCheckView";

	@Autowired
	private SpringViewProvider viewProvider;
	
	@Autowired		
	private LoginView loginView;

    @Override
    protected void init(VaadinRequest vaadinRequest) {
		Navigator navigator = new Navigator(this, this);
		navigator.addProvider(viewProvider);
		navigator.addView("", loginView);
    	navigator.addView(LOGIN_VIEW, loginView);
		// 先注册登录拦截，再触发首次跳转；否则未登录用户可以直接通过
		// 地址栏 #!logCheckView 绕过登录，而 LogCheckView 里取不到用户信息会直接 NPE
		navigator.addViewChangeListener(viewChangeListener);
		navigator.navigateTo(LOGIN_VIEW);
		setNavigator(navigator);
		// 统一兜底 UI 层未捕获异常（原来这个类写好了但从来没挂上去过）
		setErrorHandler(new CustomErrorHandler());
    }
    
private static ViewChangeListener viewChangeListener = new ViewChangeListener() {
		
	private static final long serialVersionUID = -333941841405607830L;

		@Override
		public boolean beforeViewChange(ViewChangeEvent event) {
			boolean isLoggedIn = VaadinSession.getCurrent().getAttribute("user") != null;
			boolean isLoginView = event.getNewView() instanceof LoginView;
	        if (!isLoggedIn && !isLoginView) {
	        	// 未登录访问受保护页面：回登录页
	        	event.getNavigator().navigateTo(LOGIN_VIEW);
	            return false;
	        }else if (isLoggedIn && isLoginView) {
	        	// 已登录还想去登录页：直接进主页面
	        	event.getNavigator().navigateTo(MAIN_VIEW);
	            return false;
	        }
	        return true;
		}
		
		@Override
		public void afterViewChange(ViewChangeEvent event) {
			
		}
	};

    @WebServlet(urlPatterns = "/*", name = "MyUIServlet", asyncSupported = true)
    @VaadinServletConfiguration(ui = MyUI.class, productionMode = true)
    public static class MyUIServlet extends VaadinServlet {

		private static final long serialVersionUID = 7232690359314390481L;
    }
}
