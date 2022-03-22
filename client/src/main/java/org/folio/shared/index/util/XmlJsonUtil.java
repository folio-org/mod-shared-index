package org.folio.shared.index.util;

import io.swagger.v3.oas.models.media.XML;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
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
    documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
    Document document = documentBuilder.parse(new InputSource(new StringReader(marcXml)));
    Element root = document.getDocumentElement();
    Element recordElement = null;
    if ("record".equals(root.getLocalName())) {
      recordElement = root;
    } else if ("collection".equals(root.getLocalName())) {
      Node node = root.getFirstChild();
      while (node != null) {
        if ("record".equals(node.getLocalName())) {
          if (recordElement != null) {
            throw new IllegalArgumentException("can not handle multiple records");
          }
          recordElement = (Element) node;
        }
        node = node.getNextSibling();
      }
    }
    if (recordElement == null) {
      throw new IllegalArgumentException("No record element found");
    }
    for (Node childNode = recordElement.getFirstChild();
         childNode != null;
         childNode = childNode.getNextSibling()) {

      if (childNode.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element childElement = (Element) childNode;
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
    }
    if (!fields.isEmpty()) {
      marcJson.put("fields", fields);
    }
    return marcJson;
  }

  static Object xmlToJson(XMLStreamReader xmlStreamReader, String skip, int event,
                          boolean arrayNode) throws XMLStreamException {

    StringBuilder text = null;
    JsonObject o = null;
    while (xmlStreamReader.hasNext()) {
      if (event == XMLStreamConstants.START_ELEMENT) {
        String localName = xmlStreamReader.getLocalName();
        if ("arr".equals(localName)) {
          JsonArray ar = new JsonArray();
          while (xmlStreamReader.hasNext()) {
            event = xmlStreamReader.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
              break;
            }
            Object x = xmlToJson(xmlStreamReader, skip, event, true);
            if (x != null) {
              ar.add(x);
            }
          }
          return ar;
        } else if (skip.equals(localName)) {
          int level = 0;
          while (xmlStreamReader.hasNext()) {
            if (event == XMLStreamConstants.END_ELEMENT) {
              level--;
              if (level == 0) {
                break;
              }
            } else if (event == XMLStreamConstants.START_ELEMENT) {
              level++;
            }
            event = xmlStreamReader.next();
          }
        } else {
          event = xmlStreamReader.next();
          if (o == null) {
            o = new JsonObject();
          }
          o.put(localName, xmlToJson(xmlStreamReader, skip, event, false));
          if (arrayNode) {
            return o;
          }
        }
      } else if (!arrayNode && event == XMLStreamConstants.CHARACTERS) {
        if (text == null) {
          text = new StringBuilder();
        }
        text.append(xmlStreamReader.getText());
      } else {
        break;
      }
      event = xmlStreamReader.next();
    }
    return o != null ? o : text;
  }

  /**
   * Convert "inventory" XML to JSON.
   * @param xml inventory XML
   * @return json object without original record
   * @throws XMLStreamException bad XML
   */
  public static JsonObject inventoryXmlToJson(String xml) throws XMLStreamException {
    InputStream stream = new ByteArrayInputStream(xml.getBytes());
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    Object o = xmlToJson(xmlStreamReader, "original", xmlStreamReader.next(), false);
    if (o instanceof JsonObject) {
      return (JsonObject) o;
    }
    throw new IllegalArgumentException("inventoryToXml failed");
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
  public static String getSubDocument(int event, XMLStreamReader reader)
      throws XMLStreamException {
    int level = 0;
    Buffer buffer = Buffer.buffer();
    for (; reader.hasNext(); event = reader.next()) {
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
    }
    return null;
  }
}
