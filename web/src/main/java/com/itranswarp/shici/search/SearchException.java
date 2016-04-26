package com.itranswarp.shici.search;

/**
 * Exception when search failed.
 * 
 * @author michael
 */
public class SearchException extends RuntimeException {

	public SearchException() {
		super();
	}

	public SearchException(String message, Throwable cause) {
		super(message, cause);
	}

	public SearchException(String message) {
		super(message);
	}

	public SearchException(Throwable cause) {
		super(cause);
	}

}
