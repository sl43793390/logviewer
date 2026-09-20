package com.so.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.server.AbstractErrorMessage;
import com.vaadin.server.DefaultErrorHandler;
import com.vaadin.server.ErrorEvent;
import com.vaadin.server.ErrorHandler;
import com.vaadin.server.ErrorMessage;
import com.vaadin.server.Page;
import com.vaadin.server.UserError;
import com.vaadin.shared.ui.ErrorLevel;
import com.vaadin.ui.AbstractComponent;
import com.vaadin.ui.Notification;
import com.vaadin.ui.Notification.Type;

public class CustomErrorHandler implements ErrorHandler {

	private static final long serialVersionUID = -2024738135613837445L;

	private static final Logger logger = LoggerFactory.getLogger(CustomErrorHandler.class);

	@Override
	public void error(ErrorEvent event) {
		// 无论能不能定位到组件，先把真实异常记下来，否则线上只能看到一句"系统繁忙"
		logger.error("UI 未捕获异常：", event.getThrowable());
		// Finds the original source of the error/exception
		AbstractComponent component = DefaultErrorHandler.findAbstractComponent(event);
		if (component != null) {
			ErrorMessage errorMessage = getErrorMessageForException(event.getThrowable());
			if (errorMessage != null) {
				new Notification(null, errorMessage.getFormattedHtmlMessage(), Type.WARNING_MESSAGE, true)
						.show(Page.getCurrent());
				return;
			}
		}
		// getErrorMessageForException 现在会返回 null，这条兜底分支才真正可达
		DefaultErrorHandler.doDefault(event);
	}

	private static ErrorMessage getErrorMessageForException(Throwable t) {

//	    PersistenceException persistenceException = getCauseOfType(t, PersistenceException.class);
//	    可根据异常类型获取不同message；persistenceException.getLocalizedMessage()
	RuntimeException runtimeException = getCauseOfType(t, RuntimeException.class);
		if (runtimeException != null && runtimeException.getLocalizedMessage() != null) {
			return new UserError(runtimeException.getLocalizedMessage(), AbstractErrorMessage.ContentMode.TEXT, ErrorLevel.ERROR);
		}
		// 返回 null 交给 DefaultErrorHandler 处理。
		// 原实现这里恒定返回一个"系统繁忙"的 ErrorMessage，下面的兜底永远走不到，
		// 所有异常都被同一句话盖掉，排查时完全看不到原因
		return null;
	}

	private static <T extends Throwable> T getCauseOfType(Throwable th, Class<T> type) {
		while (th != null) {
			if (type.isAssignableFrom(th.getClass())) {
				return (T) th;
			} else {
				th = th.getCause();
			}
		}
		return null;
	}

}
