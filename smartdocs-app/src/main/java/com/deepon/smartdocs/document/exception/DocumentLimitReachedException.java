package com.deepon.smartdocs.document.exception;

/** Per-user document cap hit (design doc section 5.6). Two concurrent creates at the boundary may both pass — accepted, documented. */
public class DocumentLimitReachedException extends RuntimeException {

    public DocumentLimitReachedException(int maxDocuments) {
        super("Document limit of " + maxDocuments + " reached for this account.");
    }
}
