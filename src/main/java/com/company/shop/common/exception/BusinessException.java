package com.company.shop.common.exception;

import org.springframework.http.HttpStatus;

public abstract class BusinessException extends RuntimeException {

	private final HttpStatus status;
	private final String errorCode;
	private final String messageKey;
	private final Object[] messageArgs;

	protected BusinessException(HttpStatus status, String message) {
		this(status, null, null, new Object[0], message);
	}

	protected BusinessException(HttpStatus status, String message, String errorCode) {
		this(status, errorCode, null, new Object[0], message);
	}

	protected BusinessException(HttpStatus status, String errorCode, String messageKey, Object... messageArgs) {
		this(status, errorCode, messageKey, messageArgs, null);
	}

	protected BusinessException(HttpStatus status, String errorCode, String messageKey, Object[] messageArgs, String fallbackMessage) {
		super(fallbackMessage);
		this.status = status;
		this.errorCode = errorCode;
		this.messageKey = messageKey;
		this.messageArgs = messageArgs != null ? messageArgs.clone() : new Object[0];
	}

	public HttpStatus getStatus() { return status; }
	public String getErrorCode() { return errorCode; }
	public String getMessageKey() { return messageKey; }
	public Object[] getMessageArgs() { return messageArgs.clone(); }
}
