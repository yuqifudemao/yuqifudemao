package cn.bookpdf.app;
import java.io.*;
import java.util.zip.*;

public class ParserTest {
    static int checks=0;
    static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static byte[] zip(String... pairs)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){for(int i=0;i<pairs.length;i+=2){z.putNextEntry(new ZipEntry(pairs[i]));z.write(pairs[i+1].getBytes("UTF-8"));z.closeEntry();}}return b.toByteArray();}
    static String parse(byte[] b,String name)throws Exception{return BookParser.parse(new ByteArrayInputStream(b),name,"自动");}
    public static void main(String[] args)throws Exception{
        ok(parse("中文<&".getBytes("UTF-8"),"book.txt").contains("中文&lt;&amp;"),"UTF8 + escaping");
        ok(parse("中文测试".getBytes("GB18030"),"book.txt").contains("中文测试"),"GB18030");
        ok(parse("中文测试".getBytes("UTF-16"),"book.txt").contains("中文测试"),"UTF16 BOM");
        byte[] epub=zip("META-INF/container.xml","<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>","OPS/book.opf","<package><manifest><item id='b' href='text/b.xhtml'/><item id='a' href='text/a.xhtml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>","OPS/text/a.xhtml","<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'><html xmlns='http://www.w3.org/1999/xhtml'><body><h1>第一章</h1><p>正文&nbsp;</p><img src='../images/a.png'/><script>alert(1)</script></body></html>","OPS/text/b.xhtml","<html><body><p>第二章</p></body></html>","OPS/images/a.png","imagebytes");
        String out=parse(epub,"book.epub");
        ok(out.indexOf("第一章")<out.indexOf("第二章"),"spine ordering");
        ok(out.contains("data:image/png;base64,"),"image path resolution");
        ok(!out.contains("alert("),"script removal");
        ok(out.contains("\u00a0"),"XHTML entity normalization");
        byte[] docx=zip("word/document.xml","<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><w:body><w:p><w:r><w:t>中文文档</w:t></w:r><a:blip r:embed='image1'/></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>","word/_rels/document.xml.rels","<Relationships><Relationship Id='image1' Target='media/a.jpg'/></Relationships>","word/media/a.jpg","jpeg");
        out=parse(docx,"book.docx");ok(out.contains("中文文档")&&out.contains("<table>")&&out.contains("表格"),"DOCX paragraphs and tables");
        ok(out.contains("data:image/jpeg;base64,"),"DOCX relationships");
        boolean rejected=false;try{BookParser.xml("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><x>&a;</x>".getBytes("UTF-8"));}catch(Exception e){rejected=true;}ok(rejected,"XXE rejection");
        rejected=false;try{BookParser.xml("<!DOCTYPE x [<!ENTITY a 'boom'>]><x>&a;</x>".getBytes("UTF-16"));}catch(Exception e){rejected=true;}ok(rejected,"UTF16 entity rejection");
        rejected=false;try{parse(new byte[]{1,2},"book.mobi");}catch(IOException e){rejected=true;}ok(rejected,"unsupported format");
        rejected=false;try{BookParser.read(new ByteArrayInputStream(new byte[11]),10);}catch(IOException e){rejected=true;}ok(rejected,"input size limit");
        ok(BookParser.resolve("OPS/text/a.xhtml","../images/cover%20art.png").equals("OPS/images/cover art.png"),"URI paths");
        System.out.println("PASS "+checks+" parser checks");
    }
}
