package com.onesay.assistant.v2.errand.web;

import org.json.JSONObject;

/**
 * ★ lane C3-2：注入 WebView 的 JS（纯字符串，JVM 可测其拼装 / 转义）。
 *
 * <ul>
 *   <li>{@link #EXTRACT}：抽正文（article / main / body）和可交互元素（打 {@code data-bxid} 编号，最多 80 个，只要可见的），
 *       顺带报告密码框 / 验证码控件 / 滚动位置 / 内容签名；</li>
 *   <li>{@link #click} / {@link #type} / {@link #scroll} / {@link #nextPage}：按编号操作，返回 {@code {ok, err, value, sy}}。</li>
 * </ul>
 * 所有用户 / 模型给的字符串都经 {@link JSONObject#quote} 转成 JS 字面量，不拼进代码。
 */
public final class PageScript {

    private PageScript() {}

    public static final int MAX_TEXT = 6000;
    public static final int MAX_ELS = 80;

    public static final String EXTRACT = "(function(){try{"
            + "var MAXT=" + MAX_TEXT + ",MAXE=" + MAX_ELS + ";"
            + "function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;"
            + "var s=getComputedStyle(e);return s.visibility!='hidden'&&s.display!='none'&&s.opacity!='0';}"
            + "function txt(e){var t=(e.innerText||e.textContent||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||e.getAttribute('placeholder')||e.getAttribute('alt')||'');"
            + "if(!t&&e.querySelector){var im=e.querySelector('img[alt]');if(im)t=im.getAttribute('alt');}"
            + "return String(t||'').replace(/\\s+/g,' ').trim().slice(0,80);}"
            + "var old=document.querySelectorAll('[data-bxid]');for(var i=0;i<old.length;i++)old[i].removeAttribute('data-bxid');"
            + "var all=document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[onclick]');"
            + "var els=[],n=0,pwd=false;"
            + "for(var i=0;i<all.length&&els.length<MAXE;i++){var e=all[i];if(!vis(e))continue;var tag=e.tagName.toLowerCase(),kind=tag;"
            + "if(tag=='input'){var ty=String(e.type||'text').toLowerCase();if(ty=='hidden')continue;kind='input:'+ty;if(ty=='password')pwd=true;}"
            + "else if(tag!='a'&&tag!='button'&&tag!='select'&&tag!='textarea')kind='role:'+(e.getAttribute('role')||'click');"
            + "var t=txt(e);if(!t&&kind.indexOf('input')!=0&&tag!='select'&&tag!='textarea')continue;"
            + "n++;e.setAttribute('data-bxid',String(n));"
            + "var href=tag=='a'?String(e.href||''):'';if(href.indexOf('javascript:')==0)href='';"
            + "var val=((kind.indexOf('input:')==0&&kind!='input:password')||tag=='textarea'||tag=='select')?String(e.value||'').slice(0,80):'';"
            // 守卫专用标签（aria-label/title/alt + 去空白的完整文字前 160 字），防「图标按钮 + aria-label」与「前缀凑长截断」
            + "var lab=[e.getAttribute('aria-label'),e.getAttribute('title'),e.getAttribute('alt')];"
            + "if(e.querySelector){var ia=e.querySelectorAll('img[alt],[aria-label]');for(var q=0;q<ia.length&&q<4;q++)lab.push(ia[q].getAttribute('alt')||ia[q].getAttribute('aria-label'));}"
            + "lab.push(String(e.innerText||e.textContent||'').replace(/[\\s\\u200b-\\u200f\\u2060\\ufeff]+/g,'').slice(0,160));"
            + "var ls='';for(var q=0;q<lab.length;q++){if(lab[q])ls+=String(lab[q]).slice(0,80)+' ';}"
            + "els.push({i:n,tag:tag,kind:kind,text:t,href:href.slice(0,300),value:val,lab:ls.slice(0,400)});}"
            + "var m=document.querySelector('article')||document.querySelector('main')||document.querySelector('[role=main]')||document.body;"
            + "var text=String(m&&(m.innerText||m.textContent)||'').replace(/\\n{3,}/g,'\\n\\n').trim();"
            + "if(text.length<200&&document.body)text=String(document.body.innerText||document.body.textContent||'').trim();"
            + "var cap=!!document.querySelector('iframe[src*=captcha],iframe[src*=recaptcha],iframe[src*=hcaptcha],[class*=captcha],[id*=captcha],"
            + "[class*=nc_wrapper],[id^=nc_],[class*=geetest],[class*=slider-verify],[class*=verify-slider],[class*=JDJRV]');"
            + "var se=document.scrollingElement||document.documentElement;var sy=Math.round(window.scrollY||se.scrollTop||0);"
            + "var smax=Math.max(0,Math.round(se.scrollHeight-window.innerHeight));"
            + "var h=0,sg=location.href+'|'+text.length+'|'+text.slice(0,200)+text.slice(-200);for(var k=0;k<sg.length;k++){h=(h*31+sg.charCodeAt(k))|0;}"
            + "return JSON.stringify({url:location.href,title:document.title,text:text.slice(0,MAXT),els:els,pwd:pwd,captcha:cap,sy:sy,smax:smax,sig:String(h)});"
            + "}catch(err){return JSON.stringify({url:location.href,title:document.title,text:'',els:[],err:String(err)});}})()";

    private static final String FIND = "var e=document.querySelector('[data-bxid=\"'+id+'\"]');"
            + "if(!e)return JSON.stringify({ok:false,err:'元素不在了（页面已变化）'});";

    public static String click(int id) {
        return "(function(id){" + FIND
                + "try{e.scrollIntoView({block:'center'});}catch(x){}"
                + "if(e.tagName=='A'&&e.target&&e.target!='_self')e.target='_self';"
                + "e.click();return JSON.stringify({ok:true});})(" + id + ")";
    }

    /** 输入文字；submit=true 时随后提交（有表单就 requestSubmit，没有就补回车键事件）。密码框一律拒绝。 */
    public static String type(int id, String text, boolean submit) {
        return "(function(id,v,sub){" + FIND
                + "if(String(e.type||'').toLowerCase()=='password')return JSON.stringify({ok:false,err:'不替你填密码'});"
                + "try{e.scrollIntoView({block:'center'});}catch(x){}e.focus();"
                + "if(e.tagName=='SELECT'){var ok=false;for(var i=0;i<e.options.length;i++){if(String(e.options[i].text).indexOf(v)>=0){e.selectedIndex=i;ok=true;break;}}"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));return JSON.stringify({ok:ok,value:String(e.value),err:ok?'':'没有这个选项'});}"
                + "var p=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "if(sub){var f=e.form;if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}"
                + "else{var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true};"
                + "e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));}}"
                + "return JSON.stringify({ok:true,value:String(e.value)});})(" + id + "," + JSONObject.quote(text == null ? "" : text) + "," + submit + ")";
    }

    /** dir=+1 向下一屏，-1 向上。 */
    public static String scroll(int dir) {
        int d = dir >= 0 ? 1 : -1;
        return "(function(d){var b=Math.round(window.scrollY||0);window.scrollBy(0,d*Math.round(window.innerHeight*0.85));"
                + "var a=Math.round(window.scrollY||0);return JSON.stringify({ok:a!=b,sy:a,err:a==b?'已经到头了':''});})(" + d + ")";
    }

    /** 翻页：rel=next / 文字是「下一页」「Next」「›」「»」/ class 含 next（如京东 pn-next）的链接或按钮。 */
    public static final String NEXT_PAGE = "(function(){"
            + "var c=document.querySelector('a[rel=next],link[rel=next]');"
            + "if(c&&c.tagName=='LINK'){location.href=c.href;return JSON.stringify({ok:true,how:'rel'});}"
            + "var re=/^(下一页|下页|下一頁|后页|Next|Next page|›|»|>)$/i;"
            + "var all=document.querySelectorAll('a,button,[role=button],span[class*=next],a[class*=next]');"
            + "for(var i=0;i<all.length;i++){var e=all[i];var t=String(e.innerText||e.textContent||e.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim();"
            + "var cls=String(e.className||'');"
            + "if((re.test(t)||/(^|[\\s-])(pn-)?next([\\s-]|$)/i.test(cls))&&!/disabled/i.test(cls)&&!e.disabled){"
            + "try{e.scrollIntoView({block:'center'});}catch(x){}e.click();return JSON.stringify({ok:true,how:t||cls});}}"
            + "return JSON.stringify({ok:false,err:'没找到「下一页」'});})()";
}
