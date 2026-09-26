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
import io.github.project.openubl.xsender.models.SunatResponse;
import org.apache.cxf.binding.soap.SoapFault;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentRouteSoapFaultTest {

    @Test
    void convertsClientFaultIntoRejectedSunatResponse() {
        SoapFault fault = new SoapFault(
                "El comprobante fue registrado previamente con otros datos",
                new QName("http://schemas.xmlsoap.org/soap/envelope/", "Client.1033")
        );

        SunatResponse response = DocumentRoute.toSunatFaultResponse(fault);

        assertEquals(Status.RECHAZADO, response.getStatus());
        assertEquals(1033, response.getMetadata().getResponseCode());
        assertEquals(
                "El comprobante fue registrado previamente con otros datos",
                response.getMetadata().getDescription()
        );
    }

    @Test
    void preservesPolicyRejectionWithoutNumericCode() {
        SoapFault fault = new SoapFault(
                "No tiene el perfil para enviar comprobantes electronicos - Detalle: Rejected by policy.",
                new QName("http://schemas.xmlsoap.org/soap/envelope/", "Client")
        );

        SunatResponse response = DocumentRoute.toSunatFaultResponse(fault);

        assertEquals(Status.RECHAZADO, response.getStatus());
        assertEquals(-1, response.getMetadata().getResponseCode());
        assertEquals(
                "No tiene el perfil para enviar comprobantes electronicos - Detalle: Rejected by policy.",
                response.getMetadata().getDescription()
        );
    }

    @Test
    void treatsServerFaultAsProcessingException() {
        SoapFault fault = new SoapFault(
                "SUNAT temporalmente no disponible",
                new QName("http://schemas.xmlsoap.org/soap/envelope/", "Server.0835")
        );

        SunatResponse response = DocumentRoute.toSunatFaultResponse(fault);

        assertEquals(Status.EXCEPCION, response.getStatus());
        assertEquals(835, response.getMetadata().getResponseCode());
    }
}
