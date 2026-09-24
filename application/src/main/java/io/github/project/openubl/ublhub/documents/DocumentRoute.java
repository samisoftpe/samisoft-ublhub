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

import io.github.project.openubl.ublhub.documents.exceptions.NoCertificateToSignFoundException;
import io.github.project.openubl.ublhub.documents.exceptions.NoUBLXMLFileCompliantException;
import io.github.project.openubl.ublhub.documents.exceptions.ProjectNotFoundException;
import io.github.project.openubl.ublhub.files.FilesManager;
import io.github.project.openubl.ublhub.files.UblhubFileConstants;
import io.github.project.openubl.ublhub.models.JobPhaseType;
import io.github.project.openubl.ublhub.models.JobRecoveryActionType;
import io.github.project.openubl.ublhub.models.jpa.entities.SunatEntity;
import io.github.project.openubl.xbuilder.content.models.standard.general.CreditNote;
import io.github.project.openubl.xbuilder.content.models.standard.general.DebitNote;
import io.github.project.openubl.xbuilder.content.models.standard.general.Invoice;
import io.github.project.openubl.xbuilder.content.models.standard.guia.DespatchAdvice;
import io.github.project.openubl.xbuilder.content.models.sunat.baja.VoidedDocuments;
import io.github.project.openubl.xbuilder.content.models.sunat.percepcionretencion.Perception;
import io.github.project.openubl.xbuilder.content.models.sunat.percepcionretencion.Retention;
import io.github.project.openubl.xbuilder.content.models.sunat.resumen.SummaryDocuments;
import io.github.project.openubl.xsender.Constants;
import io.github.project.openubl.xsender.camel.utils.CamelData;
import io.github.project.openubl.xsender.company.CompanyCredentials;
import io.github.project.openubl.xsender.company.CompanyURLs;
import io.github.project.openubl.xsender.files.BillServiceFileAnalyzer;
import io.github.project.openubl.xsender.files.BillServiceXMLFileAnalyzer;
import io.github.project.openubl.xsender.files.ZipFile;
import io.github.project.openubl.xsender.files.xml.XmlContent;
import io.github.project.openubl.xsender.models.Metadata;
import io.github.project.openubl.xsender.models.Status;
import io.github.project.openubl.xsender.models.Sunat;
import io.github.project.openubl.xsender.models.SunatResponse;
import io.github.project.openubl.xsender.sunat.BillServiceDestination;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.LoggingLevel;
import org.apache.camel.ValidationException;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.support.builder.Namespaces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.xml.sax.SAXParseException;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.github.project.openubl.xsender.camel.utils.CamelUtils.getBillServiceCamelData;

@ApplicationScoped
public class DocumentRoute extends RouteBuilder {

    private static final Logger LOG = Logger.getLogger(DocumentRoute.class);

    public static final String DOCUMENT_KIND = "kind";
    public static final String DOCUMENT_PROJECT = "project";
    public static final String DOCUMENT_RUC = "ruc";
    public static final String DOCUMENT_XML_DATA = "documentXmlData";
    public static final String DOCUMENT_ID = "documentId";
    public static final String DOCUMENT_FILE = "documentFile";
    public static final String DOCUMENT_FILE_ID = "documentFileId";
    public static final String DOCUMENT_SUNAT_DATA = "documentSunatData";
    public static final String GRE_TRANSPORT_PACKAGES = "greTransportPackages";

    public static final String SUNAT_RESPONSE = "sunatResponse";
    public static final String SUNAT_TICKET = "sunatTicket";
    public static final String SUNAT_GRE_REST = "sunatGreRest";
    public static final String JOB_PHASE = "jobPhase";
    public static final String JOB_RECOVERY_ACTION = "jobRecoveryAction";
    private static final String SUNAT_SOAP_URL = "sunatSoapUrl";
    private static final String SUNAT_SOAP_OPERATION = "sunatSoapOperation";
    private static final String SUNAT_REQUEST_DISPATCHED = "sunatRequestDispatched";
    private static final String SUNAT_CONNECTOR_RETURNED = "sunatConnectorReturned";

    @Inject
    SunatGreRestClient sunatGreRestClient;

    Namespaces ns = new Namespaces("ext", "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2")
            .add("ds", "http://www.w3.org/2000/09/xmldsig#");

    @ConfigProperty(name = "openubl.storage.type")
    String storageType;

    @ConfigProperty(name = "openubl.messaging.jsm.queue")
    String jmsQueue;

    @ConfigProperty(name = "openubl.messaging.sqs.queue")
    String sqsQueue;

    enum UBLDataFormat {
        INVOICE(Invoice.class),
        CREDIT_NOTE(CreditNote.class),
        DEBIT_NOTE(DebitNote.class),
        VOIDED_DOCUMENTS(VoidedDocuments.class),
        SUMMARY_DOCUMENTS(SummaryDocuments.class),
        PERCEPTION(Perception.class),
        RETENTION(Retention.class),
        DESPATCH_ADVICE(DespatchAdvice.class),;

        private final Class<?> aClass;

        UBLDataFormat(Class<?> aClass) {
            this.aClass = aClass;
        }

        public JacksonDataFormat getDataFormat() {
            JacksonDataFormat dataFormat = new JacksonDataFormat(aClass);
            dataFormat.setAutoDiscoverObjectMapper(true);
            return dataFormat;
        }
    }

    @Override
    public void configure() throws Exception {
        onException(Throwable.class)
                .onWhen(header(DOCUMENT_ID).isNotNull())
                .handled(true)
                .process(this::logSunatFailure)
                .bean("documentBean", "saveFailure");

        // Requires body=java.json.JsonObject + Optional DOCUMENT_PROJECT
        from("direct:import-json")
                .id("import-json")
                .to("direct:render-json")
                .to("direct:import-xml")
                .setBody(exchange -> DocumentImportResult.builder()
                        .documentId(exchange.getIn().getHeader(DOCUMENT_ID, Long.class))
                        .build()
                );

        from("direct:render-json")
                .id("render-json")
                .to("direct:enrich-json")
                .bean("documentBean", "render")
                .bean("documentBean", "enhanceGreShipment");

        from("direct:enrich-json")
                .id("enrich-json")
                // Validate Json
                .marshal().json(JsonLibrary.Jsonb, JsonObject.class)
                .to("json-validator:schemas/DocumentInputDto-schema.json")
                .onException(ValidationException.class)
                    .setBody(exchange -> DocumentImportResult.builder()
                            .errorMessage("JSON does not match the required schema")
                            .build()
                    )
                    .handled(true)
                    .log(LoggingLevel.DEBUG, "File does not match Schema")
                .end()

                .unmarshal().json(JsonLibrary.Jsonb, JsonObject.class)

                // Get Project
                .choice()
                    .when(header(DOCUMENT_PROJECT).isNull())
                        .process(exchange -> {
                            JsonObject json = exchange.getIn().getBody(JsonObject.class);
                            String project = json.getJsonObject("metadata").getString("project");
                            if (project != null) {
                                exchange.getIn().setHeader(DOCUMENT_PROJECT, project);
                            }
                        })
                    .endChoice()
                .end()

                // Extract document spec
                .process(exchange -> {
                    JsonObject json = exchange.getIn().getBody(JsonObject.class);

                    String kind = json.getString(DOCUMENT_KIND);
                    JsonObject document = json.getJsonObject("spec").getJsonObject("document");

                    exchange.getIn().setHeader(DOCUMENT_KIND, kind);
                    if ("DespatchAdvice".equals(kind)) {
                        exchange.getIn().setHeader(
                                GRE_TRANSPORT_PACKAGES,
                                GreShipmentXmlEnhancer.packagesFrom(document)
                        );
                        document = GreShipmentXmlEnhancer.withoutPackages(document);
                    }
                    exchange.getIn().setBody(document);
                })

                // Unmarshal to XBuilder POJO
                .choice()
                    .when(header(DOCUMENT_KIND).isEqualTo("Invoice"))
                        .marshal().json(JsonLibrary.Jsonb, Invoice.class)
                        .unmarshal(UBLDataFormat.INVOICE.getDataFormat())
                        .process(exchange -> {
                            Invoice input = exchange.getIn().getBody(Invoice.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, input.getProveedor().getRuc());
                        })
                    .endChoice()
                    .when(header(DOCUMENT_KIND).isEqualTo("CreditNote"))
                        .marshal().json(JsonLibrary.Jsonb, CreditNote.class)
                        .unmarshal(UBLDataFormat.CREDIT_NOTE.getDataFormat())
                        .process(exchange -> {
                            CreditNote input = exchange.getIn().getBody(CreditNote.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, input.getProveedor().getRuc());
                        })
                    .endChoice()
                    .when(header(DOCUMENT_KIND).isEqualTo("DebitNote"))
                        .marshal().json(JsonLibrary.Jsonb, DebitNote.class)
                        .unmarshal(UBLDataFormat.DEBIT_NOTE.getDataFormat())
                        .process(exchange -> {
                            DebitNote input = exchange.getIn().getBody(DebitNote.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, input.getProveedor().getRuc());
                        })
                    .endChoice()
                    .when(header(DOCUMENT_KIND).isEqualTo("VoidedDocuments"))
                        .marshal().json(JsonLibrary.Jsonb, VoidedDocuments.class)
                        .unmarshal(UBLDataFormat.VOIDED_DOCUMENTS.getDataFormat())
                        .process(exchange -> {
                            VoidedDocuments input = exchange.getIn().getBody(VoidedDocuments.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, input.getProveedor().getRuc());
                        })
                    .endChoice()
                    .when(header(DOCUMENT_KIND).isEqualTo("SummaryDocuments"))
                        .marshal().json(JsonLibrary.Jsonb, SummaryDocuments.class)
                        .unmarshal(UBLDataFormat.SUMMARY_DOCUMENTS.getDataFormat())
                        .process(exchange -> {
                            SummaryDocuments input = exchange.getIn().getBody(SummaryDocuments.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, input.getProveedor().getRuc());
                        })
                    .endChoice()
                    .when(header(DOCUMENT_KIND).isEqualTo("Perception"))
                        .marshal().json(JsonLibrary.Jsonb, Perception.class)
                        .unmarshal(UBLDataFormat.PERCEPTION.getDataFormat())
                        .process(exchange -> {
                            Perception input = exchange.getIn().getBody(Perception.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, input.getProveedor().getRuc());
                        })
                    .endChoice()
                    .when(header(DOCUMENT_KIND).isEqualTo("Retention"))
                        .marshal().json(JsonLibrary.Jsonb, Retention.class)
                        .unmarshal(UBLDataFormat.RETENTION.getDataFormat())
                        .process(exchange -> {
                            Retention input = exchange.getIn().getBody(Retention.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, input.getProveedor().getRuc());
                        })
                    .endChoice()
                    .when(header(DOCUMENT_KIND).isEqualTo("DespatchAdvice"))
                        .marshal().json(JsonLibrary.Jsonb, DespatchAdvice.class)
                        .unmarshal(UBLDataFormat.DESPATCH_ADVICE.getDataFormat())
                        .process(exchange -> {
                            DespatchAdvice input = exchange.getIn().getBody(DespatchAdvice.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, input.getProveedor().getRuc());
                        })
                    .endChoice()
                .end()
                .bean("documentBean", "enrich");

        // Requires body=org.w3c.dom.Document, DOCUMENT_PROJECT
        from("direct:import-xml")
                .id("import-xml")
                .bean("documentBean", "validateProject")
                .convertBodyTo(String.class)
                .onException(ProjectNotFoundException.class)
                    .setBody(exchange -> DocumentImportResult.builder()
                            .errorMessage("Project not found")
                            .build()
                    )
                    .handled(true)
                    .log(LoggingLevel.ERROR, "Project ${in.headers.project} not found")
                .end()

                .choice()
                    .when(header(DOCUMENT_RUC).isNull())
                        .setHeader(DOCUMENT_FILE, body())
                        .bean("documentBean", "generateXmlData")
                        .process(exchange -> {
                            XmlContent xmlContent = exchange.getIn().getHeader(DOCUMENT_XML_DATA, XmlContent.class);
                            exchange.getIn().setHeader(DOCUMENT_RUC, xmlContent.getRuc());
                        })
                    .otherwise()
                        .log(LoggingLevel.DEBUG, "Ruc already present")
                .end()
                .bean("documentBean", "sign")
                .onException(NoUBLXMLFileCompliantException.class)
                    .setBody(exchange -> DocumentImportResult.builder()
                            .errorMessage("No valid UBL XML file")
                            .build()
                    )
                    .handled(true)
                .end()
                .onException(NoCertificateToSignFoundException.class)
                    .setBody(exchange -> DocumentImportResult.builder()
                            .errorMessage("No certificate to sign found")
                            .build()
                    )
                    .handled(true)
                .end()
                .onException(SAXParseException.class)
                    .setBody(exchange -> DocumentImportResult.builder()
                            .errorMessage("XML could not be parsed")
                            .build()
                    )
                    .handled(true)
                .end()

                .process(exchange -> {
                    String projectName = exchange.getIn().getHeader(DocumentRoute.DOCUMENT_PROJECT, String.class);
                    List<String> baseFolder = Arrays.asList(projectName, UblhubFileConstants.XML_BASE_PATH);
                    exchange.getIn().setHeader(FilesManager.FILE_FOLDERS, baseFolder);
                })
                .setHeader("shouldZipFile", constant(true))
                .enrich("direct:" + storageType + "-save-file", (oldExchange, newExchange) -> {
                    String documentFileId = newExchange.getIn().getBody(String.class);
                    oldExchange.getIn().setHeader(DOCUMENT_FILE_ID, documentFileId);
                    return oldExchange;
                })
                .bean("documentBean", "create")
                .setBody(exchange -> exchange.getIn().getHeader(DOCUMENT_ID, Long.class))
                .choice()
                    .when(simple("{{openubl.messaging.type}}").isEqualToIgnoreCase("jvm"))
                        .to("seda:send-xml?waitForTaskToComplete=Never")
                    .endChoice()
                    .when(simple("{{openubl.messaging.type}}").isEqualToIgnoreCase("jms"))
                        .to(ExchangePattern.InOnly, "jms:queue:" + jmsQueue + "?connectionFactory=#connectionFactory")
                    .endChoice()
                    .when(simple("{{openubl.messaging.type}}").isEqualToIgnoreCase("sqs"))
                        .toD("aws2-sqs://" + sqsQueue + "?amazonSQSClient=#amazonSQSClient&autoCreateQueue=true")
                    .endChoice()
                .end()
                .setBody(exchange -> DocumentImportResult.builder()
                        .documentId(exchange.getIn().getHeader(DOCUMENT_ID, Long.class))
                        .build()
                );

        from("direct:send-xml")
                .id("send-xml")
                .setHeader(DOCUMENT_ID, body())
                .setHeader(JOB_PHASE, constant(JobPhaseType.READ_XML_FILE))
                .setHeader(JOB_RECOVERY_ACTION, constant(JobRecoveryActionType.RETRY_SEND))
                .bean("documentBean", "fetchDocument")

                .setBody(header(DocumentRoute.DOCUMENT_FILE_ID))
                .setHeader("shouldUnzip", constant(true))
                .enrich("direct:" + storageType + "-get-file", (oldExchange, newExchange) -> {
                    oldExchange.getIn().setHeader(DOCUMENT_FILE, newExchange.getIn().getBody());
                    return oldExchange;
                })

                .choice()
                    .when(header(DOCUMENT_XML_DATA).isNull())
                        .bean("documentBean", "generateXmlData")
                    .endChoice()
                .end()
                .bean("documentBean", "saveXmlData")
                .setHeader(JOB_PHASE, constant(JobPhaseType.SEND_XML_FILE))
                .bean("documentBean", "getSunatData")
                .process(exchange -> {
                    byte[] documentFile = exchange.getIn().getHeader(DOCUMENT_FILE, byte[].class);
                    SunatEntity documentSunatData = exchange.getIn().getHeader(DOCUMENT_SUNAT_DATA, SunatEntity.class);
                    XmlContent xmlContent = exchange.getIn().getHeader(DOCUMENT_XML_DATA, XmlContent.class);

                    if (isGreRest(xmlContent, documentSunatData)) {
                        exchange.getIn().setHeader(SUNAT_GRE_REST, true);
                        exchange.getIn().setHeader(JOB_RECOVERY_ACTION, JobRecoveryActionType.RETRY_FETCH_CDR);
                        try {
                            SunatGreRestClient.GreResponse response = sunatGreRestClient.submit(
                                    documentFile,
                                    xmlContent.getRuc(),
                                    xmlContent.getDocumentID(),
                                    documentSunatData
                            );
                            exchange.getIn().setBody(toSunatResponse(response));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw error;
                        }
                        return;
                    }

                    CompanyURLs urls = CompanyURLs.builder()
                            .invoice(normalizeSunatServiceUrl(documentSunatData.getSunatUrlFactura()))
                            .perceptionRetention(normalizeSunatServiceUrl(documentSunatData.getSunatUrlPercepcionRetencion()))
                            .despatch(normalizeSunatServiceUrl(documentSunatData.getSunatUrlGuiaRemision()))
                            .build();
                    CompanyCredentials credentials = CompanyCredentials.builder()
                            .username(documentSunatData.getSunatUsername())
                            .password(documentSunatData.getSunatPassword())
                            .build();

                    BillServiceFileAnalyzer fileAnalyzer = new BillServiceXMLFileAnalyzer(documentFile, urls);

                    ZipFile zipFile = fileAnalyzer.getZipFile();
                    BillServiceDestination fileDestination = fileAnalyzer.getSendFileDestination();
                    CamelData camelFileData = getBillServiceCamelData(zipFile, fileDestination, credentials);

                    exchange.setProperty(SUNAT_SOAP_URL, resolveSunatUrl(fileDestination, documentSunatData));
                    exchange.setProperty(SUNAT_SOAP_OPERATION, String.valueOf(fileDestination));
                    exchange.getIn().setBody(camelFileData.getBody());
                    camelFileData.getHeaders().forEach((k, v) -> exchange.getIn().setHeader(k, v));
                })

                .setHeader(JOB_RECOVERY_ACTION, constant(JobRecoveryActionType.RETRY_FETCH_CDR))
                .choice()
                    .when(header(SUNAT_GRE_REST).isEqualTo(true))
                        .log(LoggingLevel.DEBUG, "GRE submitted using SUNAT REST")
                    .endChoice()
                    .otherwise()
                        .process(this::logSoapRequest)
                        .to(Constants.XSENDER_BILL_SERVICE_URI)
                        .process(this::logSoapResponse)
                    .endChoice()
                .end()

                .process(exchange -> {
                    SunatResponse sunatResponse = exchange.getIn().getBody(SunatResponse.class);
                    if (sunatResponse == null) {
                        Object responseBody = exchange.getIn().getBody();
                        String responseType = responseBody != null ? responseBody.getClass().getName() : "null";
                        throw new IllegalStateException(
                                "SUNAT connector returned no valid SunatResponse (bodyType=" + responseType
                                        + ", httpStatus=" + safeHeader(exchange, "CamelHttpResponseCode") + ")"
                        );
                    }

                    byte[] cdrFile = Optional.ofNullable(sunatResponse.getSunat())
                            .map(Sunat::getCdr)
                            .orElse(null);
                    String ticket = Optional.ofNullable(sunatResponse.getSunat())
                            .map(Sunat::getTicket)
                            .orElse(null);

                    exchange.getIn().setHeader(SUNAT_RESPONSE, sunatResponse);
                    exchange.getIn().setHeader(SUNAT_TICKET, ticket);
                    exchange.getIn().setBody(cdrFile);
                })

                .choice()
                    .when(header(SUNAT_RESPONSE).isNotNull())
                        .bean("documentBean", "saveSunatResponse")
                    .endChoice()
                .end()

                .process(exchange -> {
                    String projectName = exchange.getIn().getHeader(DocumentRoute.DOCUMENT_PROJECT, String.class);
                    List<String> baseFolder = Arrays.asList(projectName, UblhubFileConstants.CDR_BASE_PATH);
                    exchange.getIn().setHeader(FilesManager.FILE_FOLDERS, baseFolder);
                })
                .choice()
                    .when(body().isNotNull())
                        .setHeader("shouldZipFile", constant(false))
                        .to("direct:" + storageType + "-save-file")
                        .bean("documentBean", "saveCdr")
                    .endChoice()
                .end()

                .choice()
                    .when(header(SUNAT_TICKET).isNotNull())
                        .setBody(header(DOCUMENT_ID))
                        .to("direct:verify-ticket")
                    .endChoice()
                .end();

        from("direct:verify-ticket")
                .id("verify-ticket")
                .setHeader(DOCUMENT_ID, body())
                .setHeader(JOB_PHASE, constant(JobPhaseType.VERIFY_TICKET))
                .setHeader(JOB_RECOVERY_ACTION, constant(JobRecoveryActionType.RETRY_FETCH_CDR))
                .bean("documentBean", "fetchDocument")
                .bean("documentBean", "getSunatData")

                .setBody(header(DocumentRoute.DOCUMENT_FILE_ID))
                .enrich("direct:" + storageType + "-get-file", (oldExchange, newExchange) -> {
                    oldExchange.getIn().setHeader(DOCUMENT_FILE, newExchange.getIn().getBody());
                    return oldExchange;
                })

                .process(exchange -> {
                    String ticket = exchange.getIn().getHeader(SUNAT_TICKET, String.class);

                    byte[] documentFile = exchange.getIn().getHeader(DOCUMENT_FILE, byte[].class);
                    SunatEntity documentSunatData = exchange.getIn().getHeader(DOCUMENT_SUNAT_DATA, SunatEntity.class);
                    XmlContent xmlContent = exchange.getIn().getHeader(DOCUMENT_XML_DATA, XmlContent.class);

                    if (isGreRest(xmlContent, documentSunatData)) {
                        SunatGreRestClient.GreResponse response = sunatGreRestClient.verify(
                                ticket,
                                xmlContent.getRuc(),
                                documentSunatData
                        );
                        exchange.getIn().setHeader(SUNAT_GRE_REST, true);
                        exchange.getIn().setBody(toSunatResponse(response));
                        return;
                    }

                    CompanyURLs urls = CompanyURLs.builder()
                            .invoice(normalizeSunatServiceUrl(documentSunatData.getSunatUrlFactura()))
                            .perceptionRetention(normalizeSunatServiceUrl(documentSunatData.getSunatUrlPercepcionRetencion()))
                            .despatch(normalizeSunatServiceUrl(documentSunatData.getSunatUrlGuiaRemision()))
                            .build();
                    CompanyCredentials credentials = CompanyCredentials.builder()
                            .username(documentSunatData.getSunatUsername())
                            .password(documentSunatData.getSunatPassword())
                            .build();

                    BillServiceFileAnalyzer fileAnalyzer = new BillServiceXMLFileAnalyzer(documentFile, urls);

                    BillServiceDestination ticketDestination = fileAnalyzer.getVerifyTicketDestination();
                    CamelData camelTicketData = getBillServiceCamelData(ticket, ticketDestination, credentials);

                    exchange.setProperty(SUNAT_SOAP_URL, resolveSunatUrl(ticketDestination, documentSunatData));
                    exchange.setProperty(SUNAT_SOAP_OPERATION, String.valueOf(ticketDestination));
                    exchange.getIn().setBody(camelTicketData.getBody());
                    camelTicketData.getHeaders().forEach((k, v) -> exchange.getIn().setHeader(k, v));
                })

                .choice()
                    .when(header(SUNAT_GRE_REST).isEqualTo(true))
                        .log(LoggingLevel.DEBUG, "GRE ticket verified using SUNAT REST")
                    .endChoice()
                    .otherwise()
                        .process(this::logSoapRequest)
                        .to(Constants.XSENDER_BILL_SERVICE_URI)
                        .process(this::logSoapResponse)
                    .endChoice()
                .end()

                .process(exchange -> {
                    SunatResponse sunatResponse = exchange.getIn().getBody(SunatResponse.class);
                    if (sunatResponse == null) {
                        Object responseBody = exchange.getIn().getBody();
                        String responseType = responseBody != null ? responseBody.getClass().getName() : "null";
                        throw new IllegalStateException(
                                "SUNAT connector returned no valid ticket response (bodyType=" + responseType
                                        + ", httpStatus=" + safeHeader(exchange, "CamelHttpResponseCode") + ")"
                        );
                    }

                    byte[] cdrFile = Optional.ofNullable(sunatResponse.getSunat())
                            .map(Sunat::getCdr)
                            .orElse(null);

                    exchange.getIn().setHeader(SUNAT_RESPONSE, sunatResponse);
                    exchange.getIn().setBody(cdrFile);
                })
                .bean("documentBean", "saveSunatResponse")
                .choice()
                    .when(body().isNotNull())
                        .process(exchange -> {
                            String projectName = exchange.getIn().getHeader(DocumentRoute.DOCUMENT_PROJECT, String.class);
                            List<String> baseFolder = Arrays.asList(projectName, UblhubFileConstants.CDR_BASE_PATH);
                            exchange.getIn().setHeader(FilesManager.FILE_FOLDERS, baseFolder);
                        })
                        .setHeader("shouldZipFile", constant(false))
                        .to("direct:" + storageType + "-save-file")
                        .bean("documentBean", "saveCdr")
                    .endChoice()
                .end();
    }

    private void logSoapRequest(Exchange exchange) {
        exchange.setProperty(SUNAT_REQUEST_DISPATCHED, true);
        exchange.setProperty(SUNAT_CONNECTOR_RETURNED, false);
        Object body = exchange.getIn().getBody();
        LOG.infof(
                "SUNAT SOAP dispatch started document=%s phase=%s url=%s operation=%s bodyType=%s headers={%s}",
                exchange.getIn().getHeader(DOCUMENT_ID),
                exchange.getIn().getHeader(JOB_PHASE),
                safeLogValue(exchange.getProperty(SUNAT_SOAP_URL)),
                safeLogValue(exchange.getProperty(SUNAT_SOAP_OPERATION)),
                body != null ? body.getClass().getName() : "null",
                diagnosticHeaders(exchange)
        );
    }

    private void logSoapResponse(Exchange exchange) {
        exchange.setProperty(SUNAT_CONNECTOR_RETURNED, true);
        Object body = exchange.getIn().getBody();
        LOG.infof(
                "SUNAT SOAP connector returned document=%s phase=%s url=%s operation=%s bodyType=%s httpStatus=%s httpText=%s headers={%s}",
                exchange.getIn().getHeader(DOCUMENT_ID),
                exchange.getIn().getHeader(JOB_PHASE),
                safeLogValue(exchange.getProperty(SUNAT_SOAP_URL)),
                safeLogValue(exchange.getProperty(SUNAT_SOAP_OPERATION)),
                body != null ? body.getClass().getName() : "null",
                safeHeader(exchange, "CamelHttpResponseCode"),
                safeHeader(exchange, "CamelHttpResponseText"),
                diagnosticHeaders(exchange)
        );
    }

    private void logSunatFailure(Exchange exchange) {
        if (exchange.getProperty(SUNAT_REQUEST_DISPATCHED) == null) {
            return;
        }
        Throwable failure = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        LOG.errorf(
                failure,
                "SUNAT SOAP dispatch failed document=%s phase=%s url=%s operation=%s dispatched=%s connectorReturned=%s httpStatus=%s headers={%s}",
                exchange.getIn().getHeader(DOCUMENT_ID),
                exchange.getIn().getHeader(JOB_PHASE),
                safeLogValue(exchange.getProperty(SUNAT_SOAP_URL)),
                safeLogValue(exchange.getProperty(SUNAT_SOAP_OPERATION)),
                exchange.getProperty(SUNAT_REQUEST_DISPATCHED),
                exchange.getProperty(SUNAT_CONNECTOR_RETURNED),
                safeHeader(exchange, "CamelHttpResponseCode"),
                diagnosticHeaders(exchange)
        );
    }

    private static String diagnosticHeaders(Exchange exchange) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Object> entry : exchange.getIn().getHeaders().entrySet()) {
            String key = entry.getKey();
            String normalized = key.toLowerCase(Locale.ROOT);
            boolean diagnostic = normalized.contains("operation")
                    || normalized.contains("soapaction")
                    || normalized.contains("endpoint")
                    || normalized.contains("destination")
                    || normalized.contains("address")
                    || normalized.contains("url")
                    || normalized.contains("uri")
                    || normalized.contains("responsecode")
                    || normalized.contains("responsetext")
                    || normalized.contains("fault");
            boolean sensitive = normalized.contains("authorization")
                    || normalized.contains("password")
                    || normalized.contains("credential")
                    || normalized.contains("token")
                    || normalized.contains("secret")
                    || normalized.contains("username");
            if (!diagnostic || sensitive || entry.getValue() instanceof byte[]
                    || entry.getValue() instanceof Map<?, ?>
                    || entry.getValue() instanceof Iterable<?>) {
                continue;
            }
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(key).append('=').append(safeLogValue(entry.getValue()));
        }
        return result.length() == 0 ? "none" : result.toString();
    }

    private static String safeHeader(Exchange exchange, String name) {
        return safeLogValue(exchange.getIn().getHeader(name));
    }

    private static String safeLogValue(Object value) {
        if (value == null) {
            return "unknown";
        }
        String safe = String.valueOf(value)
                .replaceAll("(?i)(client_secret|password|access_token|client_id|authorization)=([^&\\s]+)", "$1=***")
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer ***")
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
        return safe.length() > 500 ? safe.substring(0, 500) : safe;
    }

    private static String resolveSunatUrl(BillServiceDestination destination, SunatEntity config) {
        String operation = String.valueOf(destination).toUpperCase(Locale.ROOT);
        if (operation.contains("DESPATCH") || operation.contains("GUIA")) {
            return normalizeSunatServiceUrl(config.getSunatUrlGuiaRemision());
        }
        if (operation.contains("PERCEPTION") || operation.contains("RETENTION")) {
            return normalizeSunatServiceUrl(config.getSunatUrlPercepcionRetencion());
        }
        return normalizeSunatServiceUrl(config.getSunatUrlFactura());
    }

    private static String normalizeSunatServiceUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceFirst("(?i)\\?wsdl$", "");
    }

    private static boolean isGreRest(XmlContent xmlContent, SunatEntity config) {
        return xmlContent != null
                && "DespatchAdvice".equals(xmlContent.getDocumentType())
                && config != null
                && config.getSunatUrlGuiaRemision() != null
                && config.getSunatUrlGuiaRemision().contains("/v1/contribuyente/gem/comprobantes");
    }

    private static SunatResponse toSunatResponse(SunatGreRestClient.GreResponse response) {
        int code = 0;
        if (response.errorCode() != null) {
            try {
                code = Integer.parseInt(response.errorCode());
            } catch (NumberFormatException ignored) {
                code = -1;
            }
        }
        Status status = switch (response.state()) {
            case PENDING -> Status.UNKNOWN;
            case ACCEPTED -> statusNamed("ACEPTADO");
            case REJECTED -> statusNamed("RECHAZADO");
        };
        return SunatResponse.builder()
                .status(status)
                .sunat(Sunat.builder()
                        .ticket(response.ticket())
                        .cdr(response.cdr())
                        .build())
                .metadata(Metadata.builder()
                        .responseCode(code)
                        .description(response.description())
                        .notes(Collections.emptyList())
                        .build())
                .build();
    }

    private static Status statusNamed(String name) {
        try {
            return Status.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return Status.UNKNOWN;
        }
    }

}
