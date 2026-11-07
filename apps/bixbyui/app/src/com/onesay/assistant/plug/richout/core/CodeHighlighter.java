/*
 * [富内容输出插件 · core] OneSay 自写。只依赖 java.*。
 */
package com.onesay.assistant.plug.richout.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 轻量词法着色（一遍扫描、无回溯正则、线性时间）。不追求语法正确，只求「关键字 / 字符串 / 注释 / 数字 / 函数名 /
 * 类型 / 注解 / 标签 / 属性」分得清。超过 {@link #MAX_CHARS} 的部分不着色（照样显示）。
 *
 * <p>支持：java kotlin c cpp csharp go rust swift javascript typescript python bash json xml html svg css sql yaml
 * php ruby lua dart；其它语言（含空标签）走通用规则（字符串 / 数字 / {@code //} 与块注释）。
 */
public final class CodeHighlighter {

    private CodeHighlighter() {}

    public static final int MAX_CHARS = 20000;

    public enum Kind { KEYWORD, TYPE, STRING, NUMBER, COMMENT, FUNCTION, ANNOTATION, TAG, ATTR, VARIABLE }

    public static final class Token {
        public final int start;
        public final int end;
        public final Kind kind;

        Token(int start, int end, Kind kind) {
            this.start = start;
            this.end = end;
            this.kind = kind;
        }

        @Override public String toString() {
            return kind + "[" + start + "," + end + ")";
        }
    }

    static final class Lang {
        final Set<String> keywords;
        final Set<String> types;
        final String[] lineComments;
        final String blockOpen;
        final String blockClose;
        final String quotes;
        final boolean caseInsensitive;
        final boolean tripleQuotes;
        final char annotation;   // '@'、'#'（C 预处理）或 0

        Lang(String kw, String types, String[] lc, String bo, String bc, String quotes, boolean ci, boolean triple, char ann) {
            this.keywords = set(kw, ci);
            this.types = set(types, ci);
            this.lineComments = lc;
            this.blockOpen = bo;
            this.blockClose = bc;
            this.quotes = quotes;
            this.caseInsensitive = ci;
            this.tripleQuotes = triple;
            this.annotation = ann;
        }

        private static Set<String> set(String s, boolean ci) {
            Set<String> o = new HashSet<String>();
            if (s == null) return o;
            for (String w : s.trim().split("\\s+")) if (w.length() > 0) o.add(ci ? w.toLowerCase(Locale.ROOT) : w);
            return o;
        }
    }

    private static final String[] SLASH = {"//"};
    private static final String[] HASH = {"#"};
    private static final String[] DASH = {"--"};
    private static final String[] NONE = {};
    private static final Map<String, Lang> LANGS = new HashMap<String, Lang>();

    static {
        String cTypes = "int long short char float double void bool boolean byte unsigned signed size_t uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t auto wchar_t";
        LANGS.put("java", new Lang("abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws transient try volatile while var record yield true false null",
                "int long short char float double void boolean byte String Object Integer Long List Map Set", SLASH, "/*", "*/", "\"'", false, false, '@'));
        LANGS.put("kotlin", new Lang("as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while by catch constructor finally get import init override private protected public internal open data sealed suspend inline companion lateinit enum",
                "Int Long Short Char Float Double Unit Boolean Byte String Any List Map Set", SLASH, "/*", "*/", "\"'", false, true, '@'));
        Lang c = new Lang("break case const continue default do else enum extern for goto if inline register restrict return sizeof static struct switch typedef union volatile while NULL true false",
                cTypes, SLASH, "/*", "*/", "\"'", false, false, '#');
        LANGS.put("c", c);
        LANGS.put("cpp", new Lang("alignas alignof and asm break case catch class const constexpr const_cast continue decltype default delete do dynamic_cast else enum explicit export extern for friend goto if inline mutable namespace new noexcept not nullptr operator or private protected public register reinterpret_cast return sizeof static static_assert static_cast struct switch template this throw try typedef typeid typename union using virtual volatile while true false override final",
                cTypes + " string vector map std", SLASH, "/*", "*/", "\"'", false, false, '#'));
        LANGS.put("csharp", new Lang("abstract as base break case catch checked class const continue default delegate do else enum event explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out override params private protected public readonly ref return sealed sizeof stackalloc static struct switch this throw try typeof unchecked unsafe using virtual volatile while async await var true false null",
                "int long short char float double void bool byte string object decimal uint ulong", SLASH, "/*", "*/", "\"'", false, false, '#'));
        LANGS.put("go", new Lang("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil iota",
                "int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 float32 float64 string bool byte rune error any", SLASH, "/*", "*/", "\"'`", false, false, (char) 0));
        LANGS.put("rust", new Lang("as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while",
                "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box", SLASH, "/*", "*/", "\"", false, false, '#'));
        LANGS.put("swift", new Lang("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as catch false is nil super self Self throw throws true try async await",
                "Int Double Float String Bool Character Array Dictionary Set Any", SLASH, "/*", "*/", "\"", false, true, '@'));
        Lang js = new Lang("async await break case catch class const continue debugger default delete do else export extends finally for from function if import in instanceof let new of return static super switch this throw try typeof var void while with yield true false null undefined NaN",
                "Array Object String Number Boolean Promise Map Set JSON Math console window document", SLASH, "/*", "*/", "\"'`", false, false, '@');
        LANGS.put("javascript", js);
        LANGS.put("typescript", new Lang("abstract any as async await break case catch class const constructor continue declare default delete do else enum export extends finally for from function get if implements import in infer instanceof interface is keyof let module namespace never new of private protected public readonly return set static super switch this throw try type typeof unique unknown var void while with yield true false null undefined",
                "string number boolean object Array Object String Number Boolean Promise Map Set Record Partial", SLASH, "/*", "*/", "\"'`", false, false, '@'));
        LANGS.put("python", new Lang("and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield True False None self print",
                "int float str bool list dict set tuple bytes object range len", HASH, null, null, "\"'", false, true, '@'));
        LANGS.put("bash", new Lang("if then else elif fi case esac for while until do done in function return exit export local readonly declare unset shift source alias echo cd set test true false sudo",
                null, HASH, null, null, "\"'", false, false, (char) 0));
        LANGS.put("json", new Lang("true false null", null, NONE, null, null, "\"", false, false, (char) 0));
        LANGS.put("css", new Lang("important inherit initial unset none auto", null, NONE, "/*", "*/", "\"'", false, false, '@'));
        LANGS.put("sql", new Lang("select from where insert into values update set delete create table drop alter add index view primary key foreign references join inner left right outer full on as and or not null is in like between group by order having limit offset distinct union all case when then else end exists default unique constraint begin commit rollback with returning asc desc count sum avg min max",
                "int integer bigint smallint varchar char text date datetime timestamp boolean float double decimal numeric serial", DASH, "/*", "*/", "'\"", true, false, (char) 0));
        LANGS.put("yaml", new Lang("true false null yes no on off", null, HASH, null, null, "\"'", false, false, (char) 0));
        LANGS.put("php", new Lang("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield true false null",
                null, new String[] {"//", "#"}, "/*", "*/", "\"'", true, false, (char) 0));
        LANGS.put("ruby", new Lang("alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield require puts attr_accessor",
                null, HASH, null, null, "\"'", false, false, '@'));
        LANGS.put("lua", new Lang("and break do else elseif end false for function goto if in local nil not or repeat return then true until while",
                null, DASH, "--[[", "]]", "\"'", false, false, (char) 0));
        LANGS.put("dart", new Lang("abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for get if implements import in interface is late library mixin new null on operator part required rethrow return set show static super switch sync this throw true try typedef var void while with yield",
                "int double num String bool List Map Set Future Stream", SLASH, "/*", "*/", "\"'", false, true, '@'));
    }

    private static final Lang GENERIC = new Lang("", null, SLASH, "/*", "*/", "\"'", false, false, (char) 0);

    /** 有专门规则的语言（含 xml/html/svg 标记语言）。 */
    public static Set<String> supported() {
        Set<String> s = new HashSet<String>(LANGS.keySet());
        s.addAll(Arrays.asList("xml", "html", "svg"));
        return s;
    }

    public static List<Token> tokenize(String lang, String code) {
        List<Token> out = new ArrayList<Token>();
        if (code == null || code.length() == 0) return out;
        int n = Math.min(code.length(), MAX_CHARS);
        String l = MarkdownBlocks.normLang(lang);
        if (l.equals("xml") || l.equals("html") || l.equals("svg") || l.equals("vue")) {
            markup(code, n, out);
            return out;
        }
        Lang g = LANGS.get(l);
        if (g == null) g = GENERIC;
        if (l.equals("json")) {
            json(code, n, out);
            return out;
        }
        if (l.equals("yaml")) {
            yaml(code, n, out, g);
            return out;
        }
        generic(code, n, out, g, l);
        return out;
    }

    private static void generic(String s, int n, List<Token> out, Lang g, String lang) {
        int i = 0;
        boolean lineStart = true;
        boolean css = lang.equals("css");
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                lineStart = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            // 块注释
            if (g.blockOpen != null && s.startsWith(g.blockOpen, i)) {
                int e = s.indexOf(g.blockClose, i + g.blockOpen.length());
                e = e < 0 ? n : Math.min(n, e + g.blockClose.length());
                out.add(new Token(i, e, Kind.COMMENT));
                i = e;
                lineStart = false;
                continue;
            }
            // 行注释（bash 的 # 只在词首算，$# 不算）
            String lc = lineCommentAt(s, i, g);
            if (lc != null && !(lang.equals("bash") && i > 0 && s.charAt(i - 1) == '$')) {
                int e = s.indexOf('\n', i);
                e = e < 0 ? n : Math.min(e, n);
                out.add(new Token(i, e, Kind.COMMENT));
                i = e;
                continue;
            }
            // 预处理 / 注解
            if (g.annotation != 0 && c == g.annotation && i + 1 < n && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '[' || s.charAt(i + 1) == '!')) {
                if (g.annotation == '#' && !lineStart && !lang.equals("rust")) {
                    i++;
                    continue;
                }
                int e = i + 1;
                if (g.annotation == '#' && (lang.equals("rust"))) {
                    int rb = s.indexOf(']', i);
                    e = rb < 0 ? n : Math.min(n, rb + 1);
                } else {
                    while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '_' || s.charAt(e) == '.' || s.charAt(e) == '-')) e++;
                }
                out.add(new Token(i, e, Kind.ANNOTATION));
                i = e;
                lineStart = false;
                continue;
            }
            // 字符串
            if (g.quotes.indexOf(c) >= 0) {
                int e;
                if (g.tripleQuotes && i + 2 < n && s.charAt(i + 1) == c && s.charAt(i + 2) == c) {
                    String q = "" + c + c + c;
                    int k = s.indexOf(q, i + 3);
                    e = k < 0 ? n : Math.min(n, k + 3);
                } else {
                    e = endOfString(s, i, n, c);
                }
                out.add(new Token(i, e, Kind.STRING));
                i = e;
                lineStart = false;
                continue;
            }
            // 数字
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)) && (i == 0 || !isIdent(s.charAt(i - 1))))) {
                if (i > 0 && isIdent(s.charAt(i - 1))) {
                    i++;
                    continue;
                }
                int e = i + 1;
                while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == '.' || s.charAt(e) == '_' || s.charAt(e) == '%')) e++;
                out.add(new Token(i, e, Kind.NUMBER));
                i = e;
                lineStart = false;
                continue;
            }
            // 变量（bash $X / ${X} / php $x）
            if (c == '$' && (lang.equals("bash") || lang.equals("php")) && i + 1 < n) {
                int e = i + 1;
                if (s.charAt(e) == '{') {
                    int k = s.indexOf('}', e);
                    e = k < 0 ? n : Math.min(n, k + 1);
                } else {
                    while (e < n && (isIdent(s.charAt(e)) || (e == i + 1 && "?#@!$*0123456789".indexOf(s.charAt(e)) >= 0))) e++;
                }
                if (e > i + 1) {
                    out.add(new Token(i, e, Kind.VARIABLE));
                    i = e;
                    lineStart = false;
                    continue;
                }
            }
            // 标识符
            if (isIdentStart(c)) {
                int e = i + 1;
                while (e < n && (isIdent(s.charAt(e)) || (css && s.charAt(e) == '-'))) e++;
                String w = s.substring(i, e);
                String key = g.caseInsensitive ? w.toLowerCase(Locale.ROOT) : w;
                if (css) {
                    int k = e;
                    while (k < n && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) k++;
                    if (k < n && s.charAt(k) == ':' && !insideSelector(s, i)) out.add(new Token(i, e, Kind.ATTR));
                    else if (g.keywords.contains(key)) out.add(new Token(i, e, Kind.KEYWORD));
                } else if (g.keywords.contains(key)) {
                    out.add(new Token(i, e, Kind.KEYWORD));
                } else if (g.types.contains(key)) {
                    out.add(new Token(i, e, Kind.TYPE));
                } else {
                    int k = e;
                    while (k < n && s.charAt(k) == ' ') k++;
                    if (k < n && s.charAt(k) == '(') out.add(new Token(i, e, Kind.FUNCTION));
                    else if (Character.isUpperCase(c) && w.length() > 1 && !lang.equals("bash") && !lang.equals("sql") && hasLower(w)) out.add(new Token(i, e, Kind.TYPE));
                }
                i = e;
                lineStart = false;
                continue;
            }
            i++;
            lineStart = false;
        }
    }

    private static boolean insideSelector(String s, int i) {
        // css：属性名一定在 { 之后、对应的 } 之前
        int ob = s.lastIndexOf('{', i);
        int cb = s.lastIndexOf('}', i);
        return ob < 0 || cb > ob;
    }

    private static boolean hasLower(String w) {
        for (int k = 0; k < w.length(); k++) if (Character.isLowerCase(w.charAt(k))) return true;
        return false;
    }

    private static String lineCommentAt(String s, int i, Lang g) {
        for (String lc : g.lineComments) {
            if (s.startsWith(lc, i)) {
                if (g.blockOpen != null && s.startsWith(g.blockOpen, i)) return null;
                return lc;
            }
        }
        return null;
    }

    private static int endOfString(String s, int i, int n, char q) {
        int e = i + 1;
        while (e < n) {
            char c = s.charAt(e);
            if (c == '\\') {
                e += 2;
                continue;
            }
            if (c == q) return e + 1;
            if (c == '\n' && q != '`') return e;
            e++;
        }
        return Math.min(e, n);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static void json(String s, int n, List<Token> out) {
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"') {
                int e = endOfString(s, i, n, '"');
                int k = e;
                while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                out.add(new Token(i, e, k < n && s.charAt(k) == ':' ? Kind.ATTR : Kind.STRING));
                i = e;
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int e = i + 1;
                while (e < n && (Character.isDigit(s.charAt(e)) || ".eE+-".indexOf(s.charAt(e)) >= 0)) e++;
                out.add(new Token(i, e, Kind.NUMBER));
                i = e;
                continue;
            }
            if (Character.isLetter(c)) {
                int e = i + 1;
                while (e < n && Character.isLetter(s.charAt(e))) e++;
                String w = s.substring(i, e);
                if (w.equals("true") || w.equals("false") || w.equals("null")) out.add(new Token(i, e, Kind.KEYWORD));
                i = e;
                continue;
            }
            i++;
        }
    }

    private static void yaml(String s, int n, List<Token> out, Lang g) {
        int i = 0;
        while (i < n) {
            int le = s.indexOf('\n', i);
            if (le < 0 || le > n) le = n;
            int k = i;
            while (k < le && (s.charAt(k) == ' ' || s.charAt(k) == '-')) k++;
            if (k < le && s.charAt(k) == '#') {
                out.add(new Token(k, le, Kind.COMMENT));
            } else {
                int colon = -1;
                for (int j = k; j < le; j++) {
                    char c = s.charAt(j);
                    if (c == '"' || c == '\'') break;
                    if (c == ':' && (j + 1 == le || s.charAt(j + 1) == ' ')) {
                        colon = j;
                        break;
                    }
                }
                int vs = k;
                if (colon > k) {
                    out.add(new Token(k, colon, Kind.ATTR));
                    vs = colon + 1;
                }
                while (vs < le && s.charAt(vs) == ' ') vs++;
                int hash = -1;
                for (int j = vs; j < le; j++) if (s.charAt(j) == '#' && j > 0 && s.charAt(j - 1) == ' ') {
                    hash = j;
                    break;
                }
                int ve = hash > 0 ? hash : le;
                String v = s.substring(vs, ve).trim();
                if (v.length() > 0) {
                    Kind kd = null;
                    char f = v.charAt(0);
                    if (f == '"' || f == '\'') kd = Kind.STRING;
                    else if (Character.isDigit(f) || (f == '-' && v.length() > 1 && Character.isDigit(v.charAt(1)))) kd = Kind.NUMBER;
                    else if (g.keywords.contains(v.toLowerCase(Locale.ROOT))) kd = Kind.KEYWORD;
                    if (kd != null) out.add(new Token(vs, vs + v.length(), kd));
                }
                if (hash > 0) out.add(new Token(hash, le, Kind.COMMENT));
            }
            i = le + 1;
        }
    }

    private static void markup(String s, int n, List<Token> out) {
        int i = 0;
        while (i < n) {
            if (s.startsWith("<!--", i)) {
                int e = s.indexOf("-->", i + 4);
                e = e < 0 ? n : Math.min(n, e + 3);
                out.add(new Token(i, e, Kind.COMMENT));
                i = e;
                continue;
            }
            char c = s.charAt(i);
            if (c == '<' && i + 1 < n && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '/' || s.charAt(i + 1) == '!' || s.charAt(i + 1) == '?')) {
                int ns = i + 1;
                if (s.charAt(ns) == '/' || s.charAt(ns) == '!' || s.charAt(ns) == '?') ns++;
                int ne = ns;
                while (ne < n && (Character.isLetterOrDigit(s.charAt(ne)) || s.charAt(ne) == ':' || s.charAt(ne) == '-' || s.charAt(ne) == '_')) ne++;
                if (ne > ns) out.add(new Token(ns, ne, Kind.TAG));
                int k = ne;
                while (k < n && s.charAt(k) != '>') {
                    char ch = s.charAt(k);
                    if (ch == '"' || ch == '\'') {
                        int e = s.indexOf(ch, k + 1);
                        e = e < 0 ? n : Math.min(n, e + 1);
                        out.add(new Token(k, e, Kind.STRING));
                        k = e;
                        continue;
                    }
                    if (Character.isLetter(ch)) {
                        int e = k + 1;
                        while (e < n && (Character.isLetterOrDigit(s.charAt(e)) || s.charAt(e) == ':' || s.charAt(e) == '-' || s.charAt(e) == '_')) e++;
                        out.add(new Token(k, e, Kind.ATTR));
                        k = e;
                        continue;
                    }
                    k++;
                }
                i = Math.min(n, k + 1);
                continue;
            }
            i++;
        }
    }
}
