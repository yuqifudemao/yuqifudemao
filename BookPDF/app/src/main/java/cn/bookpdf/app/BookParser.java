package cn.bookpdf.app;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Local-only, bounded parser. Scripts, remote resources and source CSS are discarded. */
public final class BookParser {
    private static final int MAX_BYTES = 40*1024*1024;
    private static final int MAX_EXPANDED = 80*1024*1024;
    public static String parse(InputStream input, String name, String encoding) throws Exception {
        String ext=name.toLowerCase(Locale.ROOT);
        byte[] data=read(input, MAX_BYTES);
        if(ext.endsWith(".txt")) {
            String s=decode(data,encoding);
            StringBuilder b=new StringBuilder();
            for(String p:s.split("\\r?\\n",-1)) b.append("<p>").append(p.trim().isEmpty()?"&nbsp;":escape(p)).append("</p>");
            return b.toString();
        }
        if(ext.endsWith(".html")||ext.endsWith(".htm")||ext.endsWith(".xhtml")) {
            // Standalone HTML is converted through Android's HTML parser by the activity.
            return decode(data,encoding);
        }
        if(!ext.endsWith(".epub")&&!ext.endsWith(".docx")) throw new IOException("暂不支持此格式，请选择 EPUB、TXT、HTML 或 DOCX。");
        Map<String,byte[]> zip=new HashMap<>();
        int total=0,count=0;
        try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while((e=z.getNextEntry())!=null) {
                if(++count>10000) throw new IOException("压缩包内文件过多。");
                if(e.isDirectory()) continue;
                byte[] bytes=read(z,Math.min(MAX_BYTES,MAX_EXPANDED-total));
                total+=bytes.length;
                zip.put(normalize(e.getName()),bytes);
            }
        }
        if(ext.endsWith(".docx")) return docx(zip);
        if(zip.containsKey("META-INF/encryption.xml")) throw new IOException("这本 EPUB 含加密资源，暂不支持。");
        Document container=xml(required(zip,"META-INF/container.xml"));
        NodeList roots=container.getElementsByTagNameNS("*","rootfile");
        if(roots.getLength()==0) throw new IOException("EPUB 缺少书籍目录。");
        String opf=normalize(((Element)roots.item(0)).getAttribute("full-path"));
        Document book=xml(required(zip,opf));
        Map<String,String> items=new HashMap<>();
        NodeList manifest=book.getElementsByTagNameNS("*","item");
        for(int i=0;i<manifest.getLength();i++) {
            Element e=(Element)manifest.item(i);
            items.put(e.getAttribute("id"),resolve(opf,e.getAttribute("href")));
        }
        NodeList spine=book.getElementsByTagNameNS("*","itemref");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<spine.getLength();i++) {
            Element ref=(Element)spine.item(i);
            if("no".equals(ref.getAttribute("linear"))) continue;
            String path=items.get(ref.getAttribute("idref"));
            if(path==null) throw new IOException("EPUB 章节索引不完整。");
            Document chapter=xml(required(zip,path));
            NodeList bodies=chapter.getElementsByTagNameNS("*","body");
            Node root=bodies.getLength()>0?bodies.item(0):chapter.getDocumentElement();
            out.append("<section class='chapter'>");
            sanitize(root,out,zip,path);
            out.append("</section>");
            if(out.length()>MAX_EXPANDED*2) throw new IOException("书籍内容过大，请拆分后转换。");
        }
        if(out.length()==0) throw new IOException("没有找到可转换的 EPUB 章节。");
        return out.toString();
    }
    static String docx(Map<String,byte[]> zip) throws Exception {
        Document doc=xml(required(zip,"word/document.xml"));
        Map<String,String> rel=new HashMap<>();
        if(zip.containsKey("word/_rels/document.xml.rels")) {
            NodeList list=xml(zip.get("word/_rels/document.xml.rels")).getElementsByTagNameNS("*","Relationship");
            for(int i=0;i<list.getLength();i++) {
                Element e=(Element)list.item(i);
                if(!"External".equals(e.getAttribute("TargetMode"))) rel.put(e.getAttribute("Id"),resolve("word/document.xml",e.getAttribute("Target")));
            }
        }
        StringBuilder out=new StringBuilder();
        docxNode(doc.getDocumentElement(),out,zip,rel);
        if(out.length()==0) throw new IOException("文档没有可转换的正文。");
        return out.toString();
    }
    static void docxNode(Node n,StringBuilder b,Map<String,byte[]> zip,Map<String,String> rel) {
        String tag=n.getLocalName();
        if("t".equals(tag)) {b.append(escape(n.getTextContent()));return;}
        if("tab".equals(tag)) {b.append("&emsp;");return;}
        if("br".equals(tag)) {b.append("<br>");return;}
        if("blip".equals(tag)&&n instanceof Element) {
            String id=((Element)n).getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","embed");
            image(b,zip,rel.get(id));return;
        }
        String html="p".equals(tag)?"p":"tbl".equals(tag)?"table":"tr".equals(tag)?"tr":"tc".equals(tag)?"td":null;
        if(html!=null)b.append('<').append(html).append('>');
        for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())docxNode(c,b,zip,rel);
        if(html!=null)b.append("</").append(html).append('>');
    }
    static void sanitize(Node n,StringBuilder b,Map<String,byte[]>zip,String path) {
        if(n.getNodeType()==Node.TEXT_NODE||n.getNodeType()==Node.CDATA_SECTION_NODE){b.append(escape(n.getNodeValue()));return;}
        String tag=n.getLocalName();if(tag==null)tag=n.getNodeName();tag=tag.toLowerCase(Locale.ROOT);
        if(Arrays.asList("script","style","iframe","object","head","svg","form").contains(tag))return;
        if("img".equals(tag)&&n instanceof Element){image(b,zip,resolve(path,((Element)n).getAttribute("src")));return;}
        boolean allowed=Arrays.asList("p","div","span","h1","h2","h3","h4","h5","h6","blockquote","ul","ol","li","strong","em","b","i","u","sup","sub","table","thead","tbody","tr","td","th","pre","code","br","hr").contains(tag);
        if(allowed)b.append('<').append(tag).append('>');
        for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())sanitize(c,b,zip,path);
        if(allowed&&!"br".equals(tag)&&!"hr".equals(tag))b.append("</").append(tag).append('>');
    }
    static void image(StringBuilder b,Map<String,byte[]>zip,String path) {
        if(path==null)return;byte[] bytes=zip.get(path);if(bytes==null||bytes.length>8*1024*1024)return;
        String p=path.toLowerCase(Locale.ROOT),mime=p.endsWith(".png")?"image/png":p.endsWith(".jpg")||p.endsWith(".jpeg")?"image/jpeg":p.endsWith(".gif")?"image/gif":p.endsWith(".webp")?"image/webp":null;
        if(mime!=null)b.append("<img alt='' src='data:").append(mime).append(";base64,").append(Base64.getEncoder().encodeToString(bytes)).append("'>");
    }
    static Document xml(byte[] data)throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        // Allow ordinary XHTML doctype, but never internal subsets or entities.
        String source=decode(data,"自动");
        String probe=source.toUpperCase(Locale.ROOT);
        if(probe.contains("<!ENTITY")||probe.matches("(?s).*<!DOCTYPE[^>]*\\[.*"))throw new IOException("文件含不支持的 DTD 实体声明。");
        source=source.replaceAll("(?is)<!DOCTYPE[^>]*>","").replace("&nbsp;","&#160;").replace("&copy;","&#169;").replace("&mdash;","&#8212;").replace("&ndash;","&#8211;").replace("&hellip;","&#8230;");
        f.setExpandEntityReferences(false);
        DocumentBuilder builder=f.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("External entities disabled");});
        return builder.parse(new org.xml.sax.InputSource(new StringReader(source)));
    }
    static byte[] required(Map<String,byte[]>z,String key)throws IOException{byte[] b=z.get(key);if(b==null)throw new IOException("文件结构不完整："+key);return b;}
    static String normalize(String p){Deque<String>d=new ArrayDeque<>();for(String x:p.replace('\\','/').split("/")){if(x.equals("..")){if(!d.isEmpty())d.removeLast();}else if(!x.isEmpty()&&!x.equals("."))d.add(x);}return String.join("/",d);}
    static String resolve(String path,String href){try{href=java.net.URLDecoder.decode(href.replace("+","%2B"),"UTF-8");}catch(Exception ignored){}int hash=href.indexOf('#');if(hash>=0)href=href.substring(0,hash);int slash=path.lastIndexOf('/');return normalize((slash>=0?path.substring(0,slash+1):"")+href);}
    static String decode(byte[]b,String enc)throws Exception {
        if(b.length>=2&&b[0]==(byte)0xff&&b[1]==(byte)0xfe)return new String(b,2,b.length-2,"UTF-16LE");
        if(b.length>=2&&b[0]==(byte)0xfe&&b[1]==(byte)0xff)return new String(b,2,b.length-2,"UTF-16BE");
        String s;
        if(!"自动".equals(enc))s=new String(b,Charset.forName(enc));
        else try{s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}catch(CharacterCodingException ex){s=new String(b,Charset.forName("GB18030"));}
        return s.startsWith("\ufeff")?s.substring(1):s;
    }
    static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("文件过大：原文件限 40MB，解压内容限 80MB。");out.write(buf,0,n);}return out.toByteArray();}
    static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
}
