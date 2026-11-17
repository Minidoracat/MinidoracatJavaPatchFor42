import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * 遊戲 .app 的 Contents/Info.plist：只動 JVMOptions 陣列裡自家那一行。
 * 文字定位（每個 &lt;string&gt; 各占一行）與 XML 解析的結果必須相同，否則判為不可編輯（problem 非 null）；
 * 插入或刪除的永遠是一整行，所以拿掉自家行後其餘位元組與原檔相同，卸載就逐位元組回到原樣。
 */
final class InfoPlist {
    private static final String KEY = "<key>JVMOptions</key>";

    final byte[] bytes;
    final boolean bom;
    final String text;
    final String nl;
    /** JVMOptions 的值（依序）；problem 非 null 時可能不完整。 */
    final List<String> options = new ArrayList<>();
    /** 每個值所在整行的 [起, 迄)（含換行）。 */
    final List<int[]> lines = new ArrayList<>();
    int closeLineStart = -1;
    String workingDirectory;
    boolean hasClassPath;
    String problem;

    private InfoPlist(byte[] bytes) {
        this.bytes = bytes;
        this.bom = bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF;
        String t;
        try {
            t = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0))).toString();
        } catch (CharacterCodingException e) {
            t = null;
        }
        this.text = t;
        this.nl = t != null && t.contains("\r\n") ? "\r\n" : "\n";
    }

    static InfoPlist parse(byte[] bytes) {
        InfoPlist p = new InfoPlist(bytes);
        if (p.text == null) {
            p.problem = "not UTF-8 text (binary plist?)";
            return p;
        }
        try {
            p.locate();
            if (p.problem == null) p.crossCheck();
        } catch (RuntimeException e) {
            p.problem = "cannot read: " + e.getMessage();
        }
        return p;
    }

    private void locate() {
        String t = text;
        int at = t.indexOf(KEY);
        if (at < 0 || t.indexOf(KEY, at + 1) >= 0) {
            problem = at < 0 ? "no JVMOptions" : "JVMOptions appears more than once";
            return;
        }
        if (t.contains("<!--")) {
            problem = "has comments";
            return;
        }
        int i = skipWs(at + KEY.length());
        if (!t.startsWith("<array>", i)) {
            problem = "JVMOptions is not a non-empty array";
            return;
        }
        i += "<array>".length();
        while (true) {
            i = skipWs(i);
            if (t.startsWith("</array>", i)) {
                if (!onlyWsBefore(i)) {
                    problem = "</array> is not on its own line";
                    return;
                }
                closeLineStart = t.lastIndexOf('\n', i - 1) + 1;
                break;
            }
            if (!t.startsWith("<string>", i)) {
                problem = "JVMOptions has an element that is not <string>";
                return;
            }
            int end = t.indexOf("</string>", i);
            if (end < 0) {
                problem = "unterminated <string>";
                return;
            }
            String raw = t.substring(i + "<string>".length(), end);
            if (raw.indexOf('<') >= 0 || raw.contains("&#")) {
                problem = "unsupported markup inside <string>";
                return;
            }
            int after = end + "</string>".length();
            int eol = t.indexOf('\n', after);
            if (!onlyWsBefore(i) || eol < 0 || !t.substring(after, eol).isBlank()) {
                problem = "a JVMOptions <string> is not on its own line";
                return;
            }
            lines.add(new int[] { t.lastIndexOf('\n', i - 1) + 1, eol + 1 });
            options.add(unescape(raw));
            i = after;
        }
        if (options.isEmpty()) problem = "JVMOptions is empty";
    }

    private boolean onlyWsBefore(int i) {
        int ls = text.lastIndexOf('\n', i - 1) + 1;
        return text.substring(ls, i).isBlank();
    }

    private int skipWs(int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    /** XML 解析器看到的頂層 dict 必須給出同一份 JVMOptions；順便讀 WorkingDirectory、JVMClassPath。不連網、不載入外部 DTD。 */
    private void crossCheck() throws RuntimeException {
        Element dict;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((pub, sys) -> new InputSource(new StringReader("")));
            b.setErrorHandler(null);
            Element root = b.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            dict = firstChild(root);
            if (!"plist".equals(root.getTagName()) || dict == null || !"dict".equals(dict.getTagName())) {
                problem = "top level is not <plist><dict>";
                return;
            }
        } catch (Exception e) {
            problem = "XML: " + e.getMessage();
            return;
        }
        List<String> parsed = null;
        for (Element k = firstChild(dict); k != null; k = next(next(k))) {
            Element v = next(k);
            if (!"key".equals(k.getTagName()) || v == null) {
                problem = "malformed dict";
                return;
            }
            String key = k.getTextContent();
            if (key.equals("JVMOptions")) {
                parsed = new ArrayList<>();
                for (Element s = firstChild(v); s != null; s = next(s)) {
                    if (!"string".equals(s.getTagName())) {
                        problem = "JVMOptions has an element that is not <string>";
                        return;
                    }
                    parsed.add(s.getTextContent());
                }
            } else if (key.equals("WorkingDirectory")) {
                workingDirectory = v.getTextContent();
            } else if (key.equals("JVMClassPath")) {
                hasClassPath = true;
            }
        }
        if (!options.equals(parsed)) problem = "JVMOptions text and XML do not agree";
    }

    private static Element firstChild(Node n) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) return e;
        return null;
    }

    private static Element next(Node n) {
        if (n == null) return null;
        for (Node c = n.getNextSibling(); c != null; c = c.getNextSibling()) if (c instanceof Element e) return e;
        return null;
    }

    /**
     * 拿掉所有 ours 的行；newArg 非 null 時在剩下的第一個選項前插一行（排第一，比其他 agent 先跑）。
     * 沒有變動回 null。寫回前驗證：重新解析的選項正好是預期，且兩邊拿掉自家行後逐位元組相同。
     */
    byte[] edit(Predicate<String> ours, String newArg) {
        if (problem != null) throw new IllegalStateException("Info.plist is not editable: " + problem);
        List<String> expected = new ArrayList<>();
        if (newArg != null) expected.add(newArg);
        for (String o : options) if (!ours.test(o)) expected.add(o);
        if (expected.equals(options)) return null;
        String stripped = strip(ours);
        String result = stripped;
        if (newArg != null) {
            int first = -1;
            String indent = null;
            for (int k = 0; k < options.size(); k++) {
                int[] span = lines.get(k);
                String lineIndent = text.substring(span[0], text.indexOf('<', span[0]));
                if (indent == null) indent = lineIndent;
                if (!ours.test(options.get(k))) {
                    indent = lineIndent;
                    first = span[0] - removedBefore(ours, span[0]);
                    break;
                }
            }
            if (first < 0) first = closeLineStart - removedBefore(ours, closeLineStart);
            result = stripped.substring(0, first) + indent + "<string>" + escape(newArg) + "</string>" + nl + stripped.substring(first);
        }
        InfoPlist check = parse(encode(result));
        if (check.problem != null || !check.options.equals(expected) || !check.strip(ours).equals(stripped)) {
            throw new IllegalStateException("rewritten Info.plist did not pass the check");
        }
        return encode(result);
    }

    private int removedBefore(Predicate<String> ours, int pos) {
        int n = 0;
        for (int k = 0; k < options.size(); k++) {
            int[] span = lines.get(k);
            if (span[1] <= pos && ours.test(options.get(k))) n += span[1] - span[0];
        }
        return n;
    }

    private String strip(Predicate<String> ours) {
        StringBuilder b = new StringBuilder();
        int from = 0;
        for (int k = 0; k < options.size(); k++) {
            if (!ours.test(options.get(k))) continue;
            int[] span = lines.get(k);
            b.append(text, from, span[0]);
            from = span[1];
        }
        return b.append(text, from, text.length()).toString();
    }

    private byte[] encode(String t) {
        byte[] body = t.getBytes(StandardCharsets.UTF_8);
        if (!bom) return body;
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
