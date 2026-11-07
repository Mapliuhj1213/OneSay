package com.onesay.assistant.v2.errand.web;

/**
 * ★ fix/shop-research（11-03 14:37）：{@link BrowserReader} 注入的只读脚本（evaluateJavascript）。
 *
 * <p>脚本只<b>读</b> DOM：不点击、不输入、不改页面、不发请求、不碰 Cookie / storage、不调用任何 Java 接口（本 WebView 不挂 JS 接口）。
 * 解析尽量留给 Java（{@link ReaderParser}，JVM 可测）：脚本只收集原始片段 ——
 * <ul>
 *   <li>{@link #PROBE}：等渲染用，数价格节点、正文长度、有没有可见密码框；</li>
 *   <li>{@link #EXTRACT}：正文（innerText）、「下载 App / 打开 App」浮层文字（Java 据此从正文里剔掉）、
 *       价格卡片（价格节点向上找到带商品链接的祖先：卡片文字、价格原文、最长的商品链接文字与地址、店铺节点文字）、
 *       商品页主信息（标题、首屏第一个价格、店铺）。</li>
 * </ul>
 * 选择器是通用的（class 含 price / shop / store / seller / mall），外加京东（.gl-item .p-price .p-name .p-shop、.sku-name）、
 * 值得买（.z-highlight、.feed-block-title）这些已知节点作为补充；页面改版时通用规则兜底。
 */
public final class ReaderScript {

    private ReaderScript() {}

    /** 价格节点选择器（脚本与测试共用）。 */
    static final String PRICE_SEL = "[class*=\"price\"],[class*=\"Price\"],[id*=\"price\"],[class*=\"z-highlight\"],.p-price,.J-p-price";
    static final String SHOP_SEL = "[class*=\"shop\"],[class*=\"Shop\"],[class*=\"store\"],[class*=\"Store\"],[class*=\"seller\"],"
            + "[class*=\"Seller\"],[class*=\"mall\"],.p-shop,.curr-shop";
    static final String TITLE_SEL = ".sku-name,[class*=\"sku-name\"],[class*=\"skuName\"],[class*=\"ItemTitle\"],[class*=\"itemTitle\"],"
            + "[class*=\"mainTitle\"],[class*=\"MainTitle\"],h1";
    /** 「下载 App」浮层文字特征。 */
    static final String APP_RE = "下载|打开.{0,6}(App|APP|app|客户端)|App内|APP内|app内|客户端|扫码|立即打开|去App|去APP|唤起";

    public static final String PROBE =
            "(function(){try{"
                    + "var b=document.body;var t=b?(b.innerText||''):'';"
                    + "var n=0,els=document.querySelectorAll('" + js(PRICE_SEL) + "');"
                    + "for(var i=0;i<els.length&&i<500;i++){if(/\\d/.test(els[i].textContent||''))n++;}"
                    + "var pw=false,ps=document.querySelectorAll('input[type=password]');"
                    + "for(var j=0;j<ps.length;j++){var r=ps[j].getBoundingClientRect();if(r.width>0&&r.height>0){pw=true;break;}}"
                    + "return JSON.stringify({u:location.href,len:t.length,n:n,pw:pw,rs:document.readyState});"
                    + "}catch(e){return JSON.stringify({err:String(e)});}})()";

    public static final String EXTRACT =
            "(function(){try{"
                    + "var out={u:location.href,ti:document.title||'',cards:[],ov:[],main:{},pw:false,tx:''};"
                    + "var appRe=new RegExp('" + js(APP_RE) + "');"
                    + "var priceRe=/[\\u00a5\\uffe5]\\s*\\d{1,6}(\\.\\d{1,2})?|\\d{1,6}(\\.\\d{1,2})?\\s*\\u5143/;"
                    // 浮层：fixed / sticky 且文字像「下载 App」
                    + "var ovs=[],all=document.body?document.body.getElementsByTagName('*'):[];"
                    + "for(var i=0;i<all.length&&i<8000;i++){var e=all[i];var cs=window.getComputedStyle(e);"
                    + "if(cs&&(cs.position==='fixed'||cs.position==='sticky')){var x=(e.innerText||'').replace(/\\s+/g,' ').trim();"
                    + "if(x.length>0&&x.length<300&&appRe.test(x)){ovs.push(e);out.ov.push(x.slice(0,200));}}}"
                    + "function inOv(e){for(var k=0;k<ovs.length;k++){if(ovs[k].contains(e))return true;}return false;}"
                    + "function txt(e){return ((e&&(e.innerText||e.textContent))||'').replace(/\\s+/g,' ').trim();}"
                    + "function bestA(n){var as=n.querySelectorAll('a[href]'),best=null,bl=0;"
                    + "if(n.tagName==='A'&&n.href){var s0=(n.getAttribute('title')||txt(n));if(!priceRe.test(s0)){best=n;bl=s0.length;}}"
                    + "for(var j=0;j<as.length&&j<40;j++){var s=(as[j].getAttribute('title')||txt(as[j]));"
                    + "if(s.length>bl&&s.length<220&&!priceRe.test(s)){best=as[j];bl=s.length;}}return bl>=6?best:null;}"
                    // 价格候选：价格类节点 + 含 ¥ 的文字节点的父元素
                    + "var cand=[],pe=document.querySelectorAll('" + js(PRICE_SEL) + "');"
                    + "for(var i2=0;i2<pe.length&&i2<600;i2++)cand.push(pe[i2]);"
                    + "if(document.body){var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),c=0;"
                    + "while(tw.nextNode()&&c<4000){c++;var v=tw.currentNode.nodeValue;"
                    + "if(v&&/[\\u00a5\\uffe5]|\\d\\s*\\u5143/.test(v)&&tw.currentNode.parentElement)cand.push(tw.currentNode.parentElement);}}"
                    + "var seen=[];"
                    + "for(var i3=0;i3<cand.length&&out.cards.length<60;i3++){var el=cand[i3];if(inOv(el))continue;"
                    + "var pt=txt(el);if(!/\\d/.test(pt)||pt.length>60)continue;"
                    + "var card=el,a=null;for(var up=0;up<8&&card&&card!==document.body;up++){a=bestA(card);"
                    + "if(a&&txt(card).length>=15)break;a=null;card=card.parentElement;}"
                    + "if(!card||card===document.body||!a)continue;if(seen.indexOf(card)>=0)continue;seen.push(card);"
                    + "var ct=txt(card);if(ct.length>600)ct=ct.slice(0,600);"
                    + "var se=card.querySelector('" + js(SHOP_SEL) + "');var sh=se?txt(se).slice(0,40):'';"
                    + "out.cards.push({t:ct,p:pt.slice(0,40),a:((a.getAttribute('title')||txt(a))||'').slice(0,160),h:a.href||'',s:sh});}"
                    // 商品页主信息
                    + "var h=document.querySelector('" + js(TITLE_SEL) + "');out.main.t=h?txt(h).slice(0,200):'';"
                    + "var top=1e9,bp='';for(var i4=0;i4<cand.length&&i4<600;i4++){var e4=cand[i4];if(inOv(e4))continue;"
                    + "var p4=txt(e4);if(!/\\d/.test(p4)||p4.length>40)continue;var r4=e4.getBoundingClientRect();"
                    + "if(r4.width>0&&r4.height>0&&r4.top<1400&&r4.top<top){top=r4.top;bp=p4;}}out.main.p=bp;"
                    + "var s5=document.querySelector('[class*=\"shopName\"],[class*=\"shop-name\"],[class*=\"ShopName\"],.J-hove-wrap .name a,.popbox-inner .mt h3 a,.name a[href*=\"mall.jd.com\"]');"
                    + "out.main.s=s5?txt(s5).slice(0,40):'';"
                    + "var ps=document.querySelectorAll('input[type=password]');"
                    + "for(var j6=0;j6<ps.length;j6++){var r6=ps[j6].getBoundingClientRect();if(r6.width>0&&r6.height>0){out.pw=true;break;}}"
                    + "out.tx=document.body?(document.body.innerText||'').slice(0," + ReaderPolicy.TEXT_MAX + "):'';"
                    + "return JSON.stringify(out);"
                    + "}catch(e){return JSON.stringify({err:String(e)});}})()";

    /** 单引号 JS 字符串里的转义。 */
    static String js(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
