package app.shell;

import org.json.JSONObject;

/** JS для подстановки логина/пароля и данных карты в форму открытой страницы. */
final class Fill {
    private Fill() {}

    private static final String COMMON = """
            function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0&&!e.disabled&&e.type!=='hidden';}
            function setv(e,v){var st=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');
              e.focus();if(st&&st.set)st.set.call(e,v);else e.value=v;
              e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
            var all=[].slice.call(d.querySelectorAll('input')).filter(vis);
            """;

    private static final String LOGIN = """
            (function(d){var D=__DATA__;
            """ + COMMON + """
            var pw=all.filter(function(e){return e.type==='password'})[0];
            var txt=all.filter(function(e){return /^(text|email|tel|number|)$/i.test(e.type)});
            var user=null;
            if(d.activeElement&&txt.indexOf(d.activeElement)>=0)user=d.activeElement;
            else if(pw){var i=all.indexOf(pw);for(var k=i-1;k>=0;k--){if(txt.indexOf(all[k])>=0){user=all[k];break;}}}
            else user=txt[0];
            if(user&&D.u)setv(user,D.u);
            if(pw&&D.p)setv(pw,D.p);
            })(document);
            """;

    private static final String CARD = """
            (function(d){var D=__DATA__;
            """ + COMMON + """
            function ac(e){return (e.getAttribute('autocomplete')||'').toLowerCase().split(' ');}
            function hint(e){return (e.name+' '+e.id+' '+(e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')).toLowerCase();}
            function find(token,re,skip){
              for(var i=0;i<all.length;i++){if(all[i]!==skip&&ac(all[i]).indexOf(token)>=0)return all[i];}
              for(var j=0;j<all.length;j++){if(all[j]!==skip&&re.test(hint(all[j])))return all[j];}
              return null;}
            var n=find('cc-number',/card.?num|cardnumber|ccnum|номер.{0,8}карт/);
            var h=find('cc-name',/holder|card.?name|владел|держател/);
            var m=find('cc-exp-month',/exp.{0,6}month|month|мес/);
            var y=find('cc-exp-year',/exp.{0,6}year|year|год/);
            var x=(m&&y)?null:find('cc-exp',/exp|valid|срок|mm.?.?yy/);
            var c=find('cc-csc',/cvv|cvc|csc|cvv2|код/);
            if(n)setv(n,D.n);
            if(h)setv(h,D.h);
            if(m&&y){var p=D.e.split(/[\\/ .-]+/);setv(m,p[0]||'');var yy=p[1]||'';if(y.maxLength===4&&yy.length===2)yy='20'+yy;setv(y,yy);}
            else if(x)setv(x,D.e);
            if(c)setv(c,D.c);
            })(document);
            """;

    static String login(String user, String pass) {
        try {
            return LOGIN.replace("__DATA__", new JSONObject().put("u", user).put("p", pass).toString());
        } catch (Exception e) {
            return "";
        }
    }

    static String card(String holder, String number, String exp, String cvv) {
        try {
            return CARD.replace("__DATA__", new JSONObject().put("h", holder)
                    .put("n", number.replaceAll("\\s", "")).put("e", exp).put("c", cvv).toString());
        } catch (Exception e) {
            return "";
        }
    }
}
