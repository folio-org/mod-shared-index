package org.folio.shared.index.util;

import io.vertx.core.buffer.Buffer;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public class XmlJsonUtil {
  private XmlJsonUtil() { }

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
   * @param nodeName the node that is matched and is the root element of returned document
   * @param reader XML stream reader
   * @return XML document string; null if no more documents in stream
   * @throws XMLStreamException if there's an exception for the XML stream
   */
  public static String getNextSubDocument(String nodeName, XMLStreamReader reader)
      throws XMLStreamException {

    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT
          && nodeName.equals(reader.getLocalName())) {
        return getNextSubDocument(event, reader);
      }
    }
    return null;
  }

  private static String getNextSubDocument(int event, XMLStreamReader reader)
      throws XMLStreamException {
    int level = 0;
    Buffer buffer = Buffer.buffer();
    for (;;) {
      switch (event) {
        case XMLStreamConstants.START_ELEMENT:
          level++;
          buffer.appendString("<").appendString(reader.getLocalName());
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
