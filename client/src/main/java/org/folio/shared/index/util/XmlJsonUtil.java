package org.folio.shared.index.util;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XmlJsonUtil {
  private XmlJsonUtil() { }

  /**
   * Convert MARCXML to some unspecified JSON format.
   * @param marcXml MARCXML XML string
   * @return JSON object.
   * @throws SAXException some sax exception
   * @throws ParserConfigurationException problem with XML parser
   * @throws IOException other IO error
   */
  public static JsonObject convertMarcXmlToJson(String marcXml)
      throws SAXException, ParserConfigurationException, IOException {

    JsonObject marcJson = new JsonObject();
    JsonArray fields = new JsonArray();
    DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    documentBuilderFactory.setNamespaceAware(true);
    DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
    Document document = documentBuilder.parse(new InputSource(new StringReader(marcXml)));
    Element root = document.getDocumentElement();
    Element record = null;
    if (root.getTagName().equals("OAI-PMH")) { // probably a static OAI-PMH file
      Element listRecords = (Element) root.getElementsByTagName("ListRecords").item(0);
      Element topRecord = (Element) listRecords.getElementsByTagName("record").item(0);
      Element metadata = (Element) topRecord.getElementsByTagName("metadata").item(0);
      record = (Element) metadata.getElementsByTagName("record").item(0);
    } else if (root.getTagName().equals("record")) {
      NodeList recordsEmbeddedInRecord = root.getElementsByTagName("record");
      if (recordsEmbeddedInRecord != null && recordsEmbeddedInRecord.getLength() == 1) {
        // e.g. a MARC record embeddded in OAI-PMH record
        record = (Element) recordsEmbeddedInRecord.item(0);
      } else {
        record = root;
      }
    } else if (root.getLocalName().equals("collection")) {
      NodeList records = root.getElementsByTagNameNS("*", "record");
      if (records != null && records.getLength() == 1) {
        record = (Element) records.item(0);
      }
      if (records != null && records.getLength() > 1) {
        throw new IllegalArgumentException("convertMarcXmlToJson: can not handle multiple records");
      }
    }
    if (record == null) {
      throw new IllegalArgumentException("No record element found");
    }
    Node childNode = record.getFirstChild();
    Element childElement;
    while (childNode != null) {
      if (childNode.getNodeType() != record.getNodeType()) {
        childNode = childNode.getNextSibling();
        continue;
      }
      childElement = (Element) childNode;
      String textContent = childElement.getTextContent();
      if (childElement.getLocalName().equals("leader")) {
        marcJson.put("leader", textContent);
      } else if (childElement.getLocalName().equals("controlfield")) {
        JsonObject field = new JsonObject();
        String marcTag = childElement.getAttribute("tag");
        field.put(marcTag, textContent);
        fields.add(field);
      } else if (childElement.getLocalName().equals("datafield")) {
        JsonObject fieldContent = new JsonObject();
        if (childElement.hasAttribute("ind1")) {
          fieldContent.put("ind1", childElement.getAttribute("ind1"));
        }
        if (childElement.hasAttribute("ind1")) {
          fieldContent.put("ind2", childElement.getAttribute("ind2"));
        }
        JsonArray subfields = new JsonArray();
        fieldContent.put("subfields", subfields);
        NodeList nodeList = childElement.getElementsByTagNameNS("*", "subfield");
        for (int i = 0; i < nodeList.getLength(); i++) {
          Element subField = (Element) nodeList.item(i);
          String code = subField.getAttribute("code");
          String content = subField.getTextContent();
          JsonObject subfieldJson = new JsonObject();
          subfieldJson.put(code, content);
          subfields.add(subfieldJson);
        }
        JsonObject field = new JsonObject();
        String marcTag = childElement.getAttribute("tag");
        field.put(marcTag, fieldContent);
        fields.add(field);
      }
      childNode = childNode.getNextSibling();
    }
    if (fields.size() > 0) {
      marcJson.put("fields", fields);
    }
    return marcJson;
  }

  private static String encodeXmlText(String s) {
    StringBuilder res = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '<':
          res.append("&lt;");
          break;
        case '>':
          res.append("&gt;");
          break;
        case '&':
          res.append("&amp;");
          break;
        case '\"':
          res.append("&quot;");
          break;
        case '\'':
          res.append("&apos;");
          break;
        default:
          res.append(c);
      }
    }
    return res.toString();
  }

  /**
   * Returns XML serialized document for node in XML.
   *
   * <p>This method does not care about namespaces. Only elements (local names), attributes
   * and text is dealt with.
   *
   * @param event event type for node that begins the subdocument
   * @param reader XML stream reader
   * @return XML document string; null if no more documents in stream
   * @throws XMLStreamException if there's an exception for the XML stream
   */
  public static String getSubDocument(int event, XMLStreamReader reader) throws XMLStreamException {
    int level = 0;
    Buffer buffer = Buffer.buffer();
    for (;;) {
      switch (event) {
        case XMLStreamConstants.START_ELEMENT:
          level++;
          buffer.appendString("<").appendString(reader.getLocalName());
          if (level == 1) {
            String uri = reader.getNamespaceURI();
            if (uri != null) {
              buffer
                  .appendString(" xmlns=\"")
                  .appendString(uri)
                  .appendString("\"");
            }
          }
          for (int i = 0; i < reader.getAttributeCount(); i++) {
            buffer
                .appendString(" ")
                .appendString(reader.getAttributeLocalName(i))
                .appendString("=\"")
                .appendString(encodeXmlText(reader.getAttributeValue(i)))
                .appendString("\"");
          }
          buffer.appendString(">");
          break;
        case XMLStreamConstants.END_ELEMENT:
          level--;
          buffer.appendString("</").appendString(reader.getLocalName()).appendString(">");
          if (level == 0) {
            return buffer.toString();
          }
          break;
        case XMLStreamConstants.CHARACTERS:
          buffer.appendString(encodeXmlText(reader.getText()));
          break;
        default:
      }
      event = reader.next();
    }
  }
}
