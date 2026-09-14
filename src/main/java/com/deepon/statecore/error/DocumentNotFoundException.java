package com.deepon.statecore.error;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    private final UUID documentId;

    public DocumentNotFoundException(UUID documentId) {
        super("Document not found: " + documentId);
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
