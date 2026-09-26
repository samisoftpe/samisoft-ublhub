/*
 * Copyright 2019 Project OpenUBL, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.project.openubl.ublhub.documents;

import io.github.project.openubl.xsender.models.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DocumentBeanFailureTest {

    @Test
    void returnsSanitizedRootCause() {
        RuntimeException failure = new RuntimeException(
                "wrapper",
                new IllegalStateException(
                        "SUNAT failed client_id=my-client client_secret=my-secret Bearer abc.def"
                )
        );

        String description = DocumentBean.failureDescription(failure);

        assertEquals(
                "SUNAT failed client_id=*** client_secret=*** Bearer ***",
                description
        );
        assertFalse(description.contains("my-client"));
        assertFalse(description.contains("my-secret"));
        assertFalse(description.contains("abc.def"));
    }

    @Test
    void limitsDescriptionToDatabaseColumnSize() {
        String description = DocumentBean.failureDescription(
                new IllegalArgumentException("x".repeat(300))
        );

        assertEquals(255, description.length());
    }

    @Test
    void returnsSuppressedSunatFaultInsteadOfGenericWrapper() {
        IllegalStateException failure = new IllegalStateException(
                "SUNAT connector returned no valid SunatResponse"
        );
        failure.addSuppressed(new RuntimeException("No tiene el perfil para enviar comprobantes electronicos"));

        assertEquals(
                "No tiene el perfil para enviar comprobantes electronicos",
                DocumentBean.failureDescription(failure)
        );
    }

    @Test
    void convertsUnknownResponseWithoutTicketIntoException() {
        assertEquals(Status.EXCEPCION, DocumentBean.normalizeSunatStatus(Status.UNKNOWN, null));
        assertEquals(Status.EXCEPCION, DocumentBean.normalizeSunatStatus(null, null));
    }

    @Test
    void preservesProcessingResponseWhenTicketExists() {
        assertEquals(Status.UNKNOWN, DocumentBean.normalizeSunatStatus(Status.UNKNOWN, "ticket-123"));
        assertEquals(Status.UNKNOWN, DocumentBean.normalizeSunatStatus(null, "ticket-123"));
        assertEquals(Status.EN_PROCESO, DocumentBean.normalizeSunatStatus(Status.EN_PROCESO, "ticket-123"));
    }
}
