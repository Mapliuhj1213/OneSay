package com.onesay.assistant.v2.executor;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Properties;

import javax.activation.CommandInfo;
import javax.activation.CommandMap;
import javax.activation.DataContentHandler;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import static org.junit.Assert.*;

/** R14 #5：MailBridge 在没有 META-INF/mailcap（APK 里的真实情况）时也能把 base64 / QP / multipart 正文解成文字。 */
public class MailBridgeR14Test {

    // ------------------------------------------------------------ #5 MailBridge：不依赖 mailcap 也能解出正文

    /** 模拟 APK 里的情况：META-INF/mailcap 没打进包 → 没有任何 DataContentHandler。 */
    static final class EmptyCommandMap extends CommandMap {
        @Override public CommandInfo[] getPreferredCommands(String mimeType) { return new CommandInfo[0]; }
        @Override public CommandInfo[] getAllCommands(String mimeType) { return new CommandInfo[0]; }
        @Override public CommandInfo getCommand(String mimeType, String cmdName) { return null; }
        @Override public DataContentHandler createDataContentHandler(String mimeType) { return null; }
    }

    @After
    public void restoreMailcap() {
        CommandMap.setDefaultCommandMap(null);
    }

    static MimeMessage msg(String raw) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw.getBytes("ISO-8859-1")));
    }

    static String b64(String s, String cs) throws Exception {
        return java.util.Base64.getMimeEncoder().encodeToString(s.getBytes(cs));
    }

    @Test
    public void base64TextPlain_withoutMailcap_decodesToText_notStreamToString() throws Exception {
        CommandMap.setDefaultCommandMap(new EmptyCommandMap());
        String body = "你好，这是一封测试邮件：明天上午十点开会。";
        MimeMessage m = msg("Subject: t\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n" + b64(body, "UTF-8") + "\r\n");
        Object raw = m.getContent();
        assertFalse("前提：没有 mailcap 时 getContent() 给的是流（旧代码 toString 出 BASE64DecoderStream@…）", raw instanceof String);
        String text = MailBridge.extractBody(m);
        assertEquals(body, text.trim());
        assertFalse(text.contains("DecoderStream"));
    }

    @Test
    public void multipartAlternative_qpPlain_andBase64Html_withoutMailcap() throws Exception {
        CommandMap.setDefaultCommandMap(new EmptyCommandMap());
        String raw = "Subject: t\r\nMIME-Version: 1.0\r\nContent-Type: multipart/alternative; boundary=\"BB\"\r\n\r\n"
                + "--BB\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n"
                + "=E5=B7=B2=E5=8F=91=E8=B4=A7=EF=BC=8C=E5=BF=AB=E9=80=92=E5=8D=95=E5=8F=B7 SF123\r\n"
                + "--BB\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + b64("<p>已发货</p>", "UTF-8") + "\r\n--BB--\r\n";
        String text = MailBridge.extractBody(msg(raw));
        assertEquals("已发货，快递单号 SF123", text.trim());
    }

    @Test
    public void htmlOnly_gbkCharset_withoutMailcap() throws Exception {
        CommandMap.setDefaultCommandMap(new EmptyCommandMap());
        String raw = "Subject: t\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"X\"\r\n\r\n"
                + "--X\r\nContent-Type: text/html; charset=gb2312\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + b64("<div>验证码 123456 五分钟内有效</div>", "GBK") + "\r\n"
                + "--X\r\nContent-Type: application/pdf\r\nContent-Disposition: attachment; filename=a.pdf\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0x\r\n--X--\r\n";
        assertEquals("验证码 123456 五分钟内有效", MailBridge.extractBody(msg(raw)).trim());
    }

    @Test
    public void withDefaultMailcapFromClasspath_stillWorks() throws Exception {
        CommandMap.setDefaultCommandMap(null);   // JVM 测试 classpath 上有 jar 的 META-INF/mailcap（APK 里没有）
        String body = "普通邮件";
        MimeMessage m = msg("Subject: t\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n" + b64(body, "UTF-8") + "\r\n");
        assertEquals(body, MailBridge.extractBody(m).trim());
    }

    @Test
    public void charsetHelpers() {
        assertEquals("UTF-8", MailBridge.charsetOf(null));
        assertEquals("UTF-8", MailBridge.charsetOf("text/plain"));
        assertEquals("中文", MailBridge.decodeText(new byte[] {(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4}, "gb2312"));
        assertEquals("abc", MailBridge.decodeText("abc".getBytes(), "no-such-charset"));
    }

}
